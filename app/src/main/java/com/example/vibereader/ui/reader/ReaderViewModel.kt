package com.example.vibereader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.document.EBookDocumentLoader
import com.example.vibereader.data.remote.AiProviderHttpException
import com.example.vibereader.data.repository.AiConfigurationException
import com.example.vibereader.data.repository.AiFailoverManager
import com.example.vibereader.data.repository.AiRequestException
import com.example.vibereader.data.repository.AiResponseCache
import com.example.vibereader.data.repository.AiRuntimeResolver
import com.example.vibereader.data.repository.AskAiEngine
import com.example.vibereader.data.repository.BookParserRepository
import com.example.vibereader.data.repository.BookRepository
import com.example.vibereader.data.repository.DeviceBookScanner
import com.example.vibereader.data.room.BookChapterEntity
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.BookReadingThemeEntity
import com.example.vibereader.data.room.HighlightEntity
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.data.room.QuoteSource
import com.example.vibereader.data.room.ReadingProgressEntity
import com.example.vibereader.data.room.ReaderPreferenceEntity
import com.example.vibereader.domain.QuoteQualityValidator
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.prompt.AiContextSanitizer
import com.example.vibereader.domain.prompt.AiResponseSanitizer
import com.example.vibereader.model.ReadingTheme
import com.example.vibereader.util.PdfPageCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

enum class ScrollMode(val label: String) {
    HORIZONTAL("Horizontal (Swipe)"),
    VERTICAL("Vertical (Scroll)"),
}

data class ReaderSettings(
    val readingTheme: ReadingTheme = ReadingTheme.SYSTEM,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.6f,
    val marginDp: Float = 24f,
    val scrollMode: ScrollMode = ScrollMode.HORIZONTAL,
    val cropPdfWhitespace: Boolean = false,
)

data class ReaderSelection(
    val chapterIndex: Int,
    val chapterTitle: String,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val pdfHighlightRects: List<PdfSelectionRect> = emptyList(),
    val pdfStartHandle: PdfSelectionPoint? = null,
    val pdfEndHandle: PdfSelectionPoint? = null,
    /** Set when this selection was created by tapping a saved highlight. */
    val highlightId: Long? = null,
)

data class AiTaskResult(
    val title: String,
    val content: String,
    /** True when the card represents a setup, network, or provider failure. */
    val isError: Boolean = false,
    /** Re-runs the exact failed action without the user re-selecting text. */
    val retry: (() -> Unit)? = null,
)

data class ReaderUiState(
    val isLoading: Boolean = true,
    val book: BookEntity? = null,
    val chapters: List<BookChapterEntity> = emptyList(),
    val error: String? = null,
    val settings: ReaderSettings = ReaderSettings(),
    val pdfPageCount: Int = 0,
    val pdfPageIndex: Int = 0,
    val pdfPageBitmap: Bitmap? = null,
    val pdfPageBitmaps: Map<Int, Bitmap> = emptyMap(),
    val savedPdfHighlights: List<HighlightEntity> = emptyList(),
    val pdfPageText: String = "",
    val aiProcessingPageIndex: Int? = null,
    val pdfProgress: Float = 0f,
    val selection: ReaderSelection? = null,
    val speechHighlight: ReaderSelection? = null,
    val selectionVersion: Int = 0,
    val isWorking: Boolean = false,
    val message: String? = null,
    val aiResult: AiTaskResult? = null,
    val initialEpubChapter: Int = 0,
    val initialEpubScrollOffset: Int = 0,
)

class ReaderViewModel(
    context: Context,
    private val database: VibeReaderDatabase,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val bookRepository = BookRepository(
        bookDao = database.bookDao(),
        database = database,
        scanner = DeviceBookScanner(appContext),
        parserRepository = BookParserRepository(appContext, database),
    )
    private val documentLoader = EBookDocumentLoader(appContext)
    // Lazily created: EncryptedSharedPreferences/keystore setup must not run
    // during ViewModel construction (a corrupted keystore would otherwise
    // crash the reader before the user even presses an AI button).
    private val askAiEngine by lazy { AskAiEngine(appContext, responseCache = aiResponseCache) }
    private val aiResponseCache by lazy { AiResponseCache(database) }
    private val readerPreferences by lazy {
        appContext.getSharedPreferences(READER_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val stateLock = Any()
    private val renderingPages = mutableSetOf<String>()
    /** Serializes native page open/render/close for this ViewModel's renderer. */
    private val pdfRenderMutex = Mutex()
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var loadedBookId: String? = null

    /**
     * The EPUB chapter the reader is actually on right now. The selection may
     * be empty and `initialEpubChapter` only reflects the last saved location,
     * so Ask AI must use this live value to attach the correct chapter context.
     */
    @Volatile
    private var currentEpubChapter: Int = 0
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfRendererBookId: String? = null
    private var progressJob: Job? = null

    /**
     * Last progress requested for persistence. Kept outside viewModelScope so
     * [onCleared] can flush it on IO after the reader route leaves, even if
     * the debounced write was still pending.
     */
    @Volatile
    private var pendingProgress: Triple<String, String, Float>? = null
    private var pdfSelectionJob: Job? = null
    private var pdfHighlightsJob: Job? = null
    private var pdfPageTextJob: Job? = null
    private val pdfPageLayouts = mutableMapOf<Int, PdfPageLayout>()
    private val pdfLayoutLock = Any()
    private val pdfLayoutGateLock = Any()
    private val pdfLayoutGates = mutableMapOf<Int, Any>()

    /**
     * Global safety net for every coroutine in the reader. Any failure that
     * escapes a local try-catch (network errors, HTTP 4xx/5xx, JSON parsing,
     * SQLite faults, out-of-memory during PDF processing, race fallout) is
     * logged and surfaced as a non-blocking Snackbar message instead of
     * terminating the whole process.
     */
    private val uncaughtExceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Unhandled coroutine failure", error)
        showFailureMessage(error)
    }

    private fun guardedLaunch(
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = viewModelScope.launch(uncaughtExceptionHandler + dispatcher) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Handled coroutine failure", error)
            showFailureMessage(error)
        }
    }

    private fun showFailureMessage(error: Throwable) {
        val message = when (error) {
            is OutOfMemoryError ->
                "Not enough memory to finish this action. Try a smaller page or zoom level."
            is android.database.sqlite.SQLiteException ->
                "Could not save to your library. Storage may be full or busy."
            is AiProviderHttpException ->
                error.localizedMessage?.takeIf(String::isNotBlank)
                    ?: "AI service temporarily unavailable."
            is IOException ->
                error.localizedMessage?.takeIf(String::isNotBlank)
                    ?: "A network or file error occurred. Please try again."
            else ->
                error.localizedMessage?.takeIf(String::isNotBlank)
                    ?: "Something went wrong. Your book is safe. Please try again."
        }
        showMessage(message)
    }

    fun loadBook(bookId: String) {
        if (loadedBookId == bookId) return
        loadedBookId = bookId
        pdfSelectionJob?.cancel()
        pdfHighlightsJob?.cancel()
        pdfPageTextJob?.cancel()
        synchronized(pdfLayoutLock) { pdfPageLayouts.clear() }
        guardedLaunch {
            _uiState.value = ReaderUiState(isLoading = true)
            // Let the reader route and its lightweight loading frame render,
            // then wait out the 180 ms open transition before database
            // access, renderer teardown, or PDF parsing start.
            delay(OPEN_DEFERRAL_MS)
            withContext(Dispatchers.IO) { closePdfRenderer() }
            try {
                val result = withContext(Dispatchers.IO) {
                    Triple(
                        database.bookDao().getById(bookId),
                        database.bookChapterDao().getForBook(bookId),
                        database.readingProgressDao().getForBook(bookId),
                    )
                }
                val book = result.first
                    ?: throw IOException("This book is no longer available.")
                val chapters = result.second
                val savedProgress = result.third
                val savedScrollMode = withContext(Dispatchers.IO) {
                    database.readerPreferenceDao()
                        .getById()
                        ?.scrollMode
                        ?.let { storedMode ->
                            ScrollMode.entries.firstOrNull { it.name == storedMode }
                        }
                } ?: ScrollMode.HORIZONTAL
                val savedCropPdfWhitespace = withContext(Dispatchers.IO) {
                    readerPreferences.getBoolean(KEY_CROP_PDF_WHITESPACE, false)
                }
                val savedReadingTheme = withContext(Dispatchers.IO) {
                    database.bookReadingThemeDao()
                        .getForBook(bookId)
                        ?.readingTheme
                        ?.let { storedTheme ->
                            ReadingTheme.entries.firstOrNull { it.name == storedTheme }
                        }
                } ?: ReadingTheme.SYSTEM
                val location = savedProgress?.location.orEmpty()
                if (book.format.equals("PDF", ignoreCase = true)) {
                    val pageCount = openPdf(book)
                    val savedPage = location.removePrefix("pdf:").toIntOrNull() ?: 0
                    val page = savedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    _uiState.value = ReaderUiState(
                        isLoading = false,
                        book = book,
                        chapters = chapters,
                        pdfPageCount = pageCount,
                        pdfPageIndex = page,
                        pdfProgress = savedProgress?.progress ?: 0f,
                        settings = ReaderSettings(
                            scrollMode = savedScrollMode,
                            readingTheme = savedReadingTheme,
                            cropPdfWhitespace = savedCropPdfWhitespace,
                        ),
                    )
                    setPdfPageIndex(page)
                } else {
                    val savedLocation = EPUB_LOCATION_REGEX.matchEntire(location)
                    val savedChapter = savedLocation?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    currentEpubChapter = savedChapter.coerceAtLeast(0)
                    _uiState.value = ReaderUiState(
                        isLoading = false,
                        book = book,
                        chapters = chapters,
                        settings = ReaderSettings(
                            scrollMode = savedScrollMode,
                            readingTheme = savedReadingTheme,
                            cropPdfWhitespace = savedCropPdfWhitespace,
                        ),
                        initialEpubChapter = savedChapter,
                        initialEpubScrollOffset = savedLocation?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                    )
                }
                bookRepository.updateLastReadTimestamp(bookId, System.currentTimeMillis())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = ReaderUiState(
                    isLoading = false,
                    error = error.localizedMessage ?: "Could not open this book.",
                )
            }
        }
    }

    fun updateSettings(settings: ReaderSettings) {
        val state = _uiState.value
        val cropChanged = state.settings.cropPdfWhitespace != settings.cropPdfWhitespace
        _uiState.value = if (cropChanged) {
            // Drop every rendered bitmap so the toggle immediately reflects in
            // the pager; the render key also changes with the crop flag.
            state.copy(
                settings = settings,
                pdfPageBitmap = null,
                pdfPageBitmaps = emptyMap(),
            )
        } else {
            state.copy(settings = settings)
        }
        if (cropChanged) {
            guardedLaunch {
                try {
                    withContext(Dispatchers.IO) {
                        readerPreferences.edit()
                            .putBoolean(KEY_CROP_PDF_WHITESPACE, settings.cropPdfWhitespace)
                            .apply()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    showMessage(error.localizedMessage ?: "Could not save the crop setting.")
                }
            }
        }
        val bookId = state.book?.id ?: return
        if (state.settings.readingTheme == settings.readingTheme) return
        guardedLaunch {
            try {
                withContext(Dispatchers.IO) {
                    database.bookReadingThemeDao().upsert(
                        BookReadingThemeEntity(
                            bookId = bookId,
                            readingTheme = settings.readingTheme.name,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not save this book's reading theme.")
            }
        }
    }

    fun setScrollMode(mode: ScrollMode) {
        val state = _uiState.value
        if (state.settings.scrollMode == mode) return
        _uiState.value = state.copy(settings = state.settings.copy(scrollMode = mode))
        guardedLaunch {
            try {
                withContext(Dispatchers.IO) {
                    database.readerPreferenceDao().upsert(
                        ReaderPreferenceEntity(scrollMode = mode.name),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not save the PDF scroll mode.")
            }
        }
    }

    fun setSelection(selection: ReaderSelection?) {
        _uiState.value = _uiState.value.copy(selection = selection)
    }

    fun updatePdfSpeechHighlight(pageIndex: Int, start: Int, end: Int) {
        val page = synchronized(pdfLayoutLock) { pdfPageLayouts[pageIndex] }
        if (page == null || start !in page.text.indices || end <= start) {
            clearPdfSpeechHighlight()
            return
        }
        val rects = mergePdfSelectionRects(
            page.charRects.subList(start.coerceAtLeast(0), end.coerceAtMost(page.charRects.size))
                .filterNotNull(),
        )
        _uiState.value = _uiState.value.copy(
            speechHighlight = rects.takeIf { it.isNotEmpty() }?.let {
                ReaderSelection(
                    chapterIndex = pageIndex,
                    chapterTitle = "Page ${pageIndex + 1}",
                    text = "",
                    startOffset = start,
                    endOffset = end,
                    pdfHighlightRects = it,
                )
            },
        )
    }

    fun clearPdfSpeechHighlight() {
        if (_uiState.value.speechHighlight != null) {
            _uiState.value = _uiState.value.copy(speechHighlight = null)
        }
    }

    fun selectPdfTextAt(pageIndex: Int, xFraction: Float, yFraction: Float) {
        selectPdfTextRangeInternal(
            pageIndex = pageIndex,
            startX = xFraction,
            startY = yFraction,
            endX = xFraction,
            endY = yFraction,
            highlightId = null,
            movingHandle = null,
            previousStart = null,
            previousEnd = null,
        )
    }

    fun movePdfSelectionHandle(
        pageIndex: Int,
        isStartHandle: Boolean,
        xFraction: Float,
        yFraction: Float,
    ) {
        val selection = _uiState.value.selection
            ?.takeIf { it.chapterIndex == pageIndex } ?: return
        val start = selection.pdfStartHandle ?: return
        val end = selection.pdfEndHandle ?: return
        selectPdfTextRangeInternal(
            pageIndex = pageIndex,
            startX = if (isStartHandle) xFraction else start.x,
            startY = if (isStartHandle) yFraction else start.y,
            endX = if (isStartHandle) end.x else xFraction,
            endY = if (isStartHandle) end.y else yFraction,
            highlightId = null,
            movingHandle = isStartHandle,
            previousStart = start,
            previousEnd = end,
        )
    }

    fun selectPdfTextRange(
        pageIndex: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ) {
        selectPdfTextRangeInternal(
            pageIndex = pageIndex,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            highlightId = null,
            movingHandle = null,
            previousStart = null,
            previousEnd = null,
        )
    }

    /**
     * Re-selects a saved highlight so the contextual menu can offer
     * Unhighlight, Copy and Save Quote without any extra PDF parsing.
     */
    fun selectPdfHighlight(highlight: HighlightEntity) {
        val state = _uiState.value
        val book = state.book?.takeIf { it.format.equals("PDF", ignoreCase = true) } ?: return
        if (highlight.bookId != book.id) return
        pdfSelectionJob?.cancel()
        pdfSelectionJob = null

        val pageIndex = (highlight.pageIndex ?: highlight.chapterIndex)
            .coerceIn(0, (state.pdfPageCount - 1).coerceAtLeast(0))
        val rects = parsePdfHighlightRects(highlight.boundsJson)
            .sortedWith(compareBy({ it.top }, { it.left }))
        val storedStart = highlight.startCharOffset
        val legacyStart = highlight.characterOffset
        val startOffset = if (storedStart > 0 || legacyStart <= 0) storedStart else legacyStart
        val storedEnd = highlight.endCharOffset
        val legacyEnd = highlight.endCharacterOffset
        val endOffset = when {
            storedEnd > startOffset -> storedEnd
            legacyEnd > startOffset -> legacyEnd
            else -> startOffset + highlight.selectedText.length
        }
        val startHandle = rects.firstOrNull()?.let {
            PdfSelectionPoint(x = it.left, y = it.centerY)
        }
        val endHandle = rects.lastOrNull()?.let {
            PdfSelectionPoint(x = it.right, y = it.centerY)
        }
        setSelection(
            ReaderSelection(
                chapterIndex = pageIndex,
                chapterTitle = highlight.note?.takeIf { it.isNotBlank() }
                    ?: "Page ${pageIndex + 1}",
                text = highlight.selectedText,
                startOffset = startOffset,
                endOffset = endOffset.coerceAtLeast(startOffset),
                pdfHighlightRects = rects,
                pdfStartHandle = startHandle,
                pdfEndHandle = endHandle,
                highlightId = highlight.id,
            ),
        )
    }

    fun unhighlightSelection() {
        val highlightId = _uiState.value.selection?.highlightId ?: return
        guardedLaunch {
            try {
                database.highlightDao().deleteById(highlightId)
                if (_uiState.value.selection?.highlightId == highlightId) {
                    clearSelection()
                }
                showMessage("Highlight removed.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not remove this highlight.")
            }
        }
    }

    private fun selectPdfTextRangeInternal(
        pageIndex: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        highlightId: Long?,
        movingHandle: Boolean?,
        previousStart: PdfSelectionPoint?,
        previousEnd: PdfSelectionPoint?,
    ) {
        val state = _uiState.value
        val book = state.book?.takeIf { it.format.equals("PDF", ignoreCase = true) } ?: return
        if (
            pageIndex !in 0 until state.pdfPageCount ||
            listOf(startX, startY, endX, endY).any { !it.isFinite() || it !in 0f..1f }
        ) return

        pdfSelectionJob?.cancel()
        pdfSelectionJob = guardedLaunch {
            try {
                val selection = withContext(Dispatchers.IO) {
                    val layout = loadPdfPageLayout(book, pageIndex)
                    buildPdfSelection(
                        pageIndex = pageIndex,
                        layout = layout,
                        startX = startX,
                        startY = startY,
                        endX = endX,
                        endY = endY,
                        highlightId = highlightId,
                        movingHandle = movingHandle,
                        previousStart = previousStart,
                        previousEnd = previousEnd,
                    )
                }
                coroutineContext.ensureActive()
                if (selection == null) {
                    if (movingHandle == null) {
                        showMessage("No selectable text was found at this position.")
                    }
                } else if (_uiState.value.book?.id == book.id) {
                    setSelection(selection)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not select PDF text.")
            }
        }
    }

    private fun buildPdfSelection(
        pageIndex: Int,
        layout: PdfPageLayout,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        highlightId: Long?,
        movingHandle: Boolean?,
        previousStart: PdfSelectionPoint?,
        previousEnd: PdfSelectionPoint?,
    ): ReaderSelection? {
        val words = layout.words
        if (words.isEmpty()) return null

        val previousStartWord = if (movingHandle == true) {
            previousStart?.let { PdfWordSnapper.snap(words, it.x, it.y) }
        } else {
            null
        }
        val previousEndWord = if (movingHandle == false) {
            previousEnd?.let { PdfWordSnapper.snap(words, it.x, it.y) }
        } else {
            null
        }
        var firstWord = PdfWordSnapper.snap(
            words = words,
            xFraction = startX,
            yFraction = startY,
            previousWord = previousStartWord,
        ) ?: return null
        var lastWord = PdfWordSnapper.snap(
            words = words,
            xFraction = endX,
            yFraction = endY,
            previousWord = previousEndWord,
        ) ?: return null

        if (firstWord.charStart > lastWord.charStart) {
            when (movingHandle) {
                true -> firstWord = lastWord
                false -> lastWord = firstWord
                null -> {
                    val swap = firstWord
                    firstWord = lastWord
                    lastWord = swap
                }
            }
        }

        val charStart = firstWord.charStart.coerceIn(0, layout.text.length)
        val charEnd = lastWord.charEnd.coerceIn(charStart, layout.text.length)
        val selectedText = layout.text.substring(charStart, charEnd).trim()
        if (selectedText.isEmpty()) return null

        val rectOf: (PdfWordBounds) -> PdfSelectionRect = { word ->
            PdfSelectionRect(word.left, word.top, word.right, word.bottom)
        }
        val highlightRects = mergePdfSelectionRects(
            layout.charRects.subList(charStart, charEnd).filterNotNull(),
        ).ifEmpty {
            mergePdfSelectionRects(
                words
                    .filter { it.charEnd > charStart && it.charStart < charEnd }
                    .map(rectOf),
            )
        }
        if (highlightRects.isEmpty()) return null

        return ReaderSelection(
            chapterIndex = pageIndex,
            chapterTitle = "Page ${pageIndex + 1}",
            text = selectedText,
            startOffset = charStart,
            endOffset = charEnd,
            pdfHighlightRects = highlightRects,
            pdfStartHandle = PdfSelectionPoint(
                x = firstWord.left.coerceIn(0f, 1f),
                y = firstWord.centerY.coerceIn(0f, 1f),
            ),
            pdfEndHandle = PdfSelectionPoint(
                x = lastWord.right.coerceIn(0f, 1f),
                y = lastWord.centerY.coerceIn(0f, 1f),
            ),
            highlightId = highlightId,
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selection = null,
            selectionVersion = _uiState.value.selectionVersion + 1,
        )
    }

    fun showMessage(message: String) {
        _uiState.value = _uiState.value.copy(message = message)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun renderPdfPage(pageIndex: Int, requestedWidthPx: Int) {
        setPdfPageIndex(pageIndex)
        requestPdfPageRender(pageIndex, requestedWidthPx)
    }

    fun setPdfPageIndex(pageIndex: Int) {
        val state = _uiState.value
        val pageCount = state.pdfPageCount
        if (pageCount <= 0) return
        val page = pageIndex.coerceIn(0, pageCount - 1)
        val pageChanged = page != state.pdfPageIndex
        if (pageChanged) {
            pdfSelectionJob?.cancel()
            pdfSelectionJob = null
        }
        val nearbyBitmaps = retainNearbyPdfPages(state.pdfPageBitmaps, page)
        _uiState.value = state.copy(
            pdfPageIndex = page,
            pdfPageBitmap = nearbyBitmaps[page],
            pdfPageBitmaps = nearbyBitmaps,
            savedPdfHighlights = if (pageChanged) emptyList() else state.savedPdfHighlights,
            pdfPageText = if (pageChanged) "" else state.pdfPageText,
            pdfProgress = (page + 1f) / pageCount,
            selection = if (pageChanged) null else state.selection,
            selectionVersion = if (pageChanged && state.selection != null) {
                state.selectionVersion + 1
            } else {
                state.selectionVersion
            },
        )
        val book = state.book
        if (book?.format?.equals("PDF", ignoreCase = true) == true) {
            pdfHighlightsJob?.cancel()
            pdfHighlightsJob = guardedLaunch {
                database.highlightDao().getHighlightsForChapter(book.id, page).collect { highlights ->
                    val current = _uiState.value
                    if (current.book?.id == book.id && current.pdfPageIndex == page) {
                        _uiState.value = current.copy(savedPdfHighlights = highlights)
                    }
                }
            }
            pdfPageTextJob?.cancel()
            pdfPageTextJob = guardedLaunch {
                try {
                    val text = withContext(Dispatchers.IO) { extractPdfPageText(book, page) }
                    val current = _uiState.value
                    if (current.book?.id == book.id && current.pdfPageIndex == page) {
                        _uiState.value = current.copy(pdfPageText = text)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    showMessage(error.localizedMessage ?: "Could not read this PDF page.")
                }
            }
        }
        persistProgress("pdf:$page", (page + 1f) / pageCount)
    }

    fun requestPdfPageRender(
        pageIndex: Int,
        requestedWidthPx: Int,
        requestedHeightPx: Int = 0,
        zoomScale: Float = 1f,
    ) {
        val state = _uiState.value
        val pageCount = state.pdfPageCount
        if (pageCount <= 0) return
        val page = pageIndex.coerceIn(0, pageCount - 1)
        val book = state.book ?: return
        if (!zoomScale.isFinite() || zoomScale < 1f) return
        val qualityScale = when {
            zoomScale < 1.35f -> 1f
            zoomScale < 1.8f -> 1.5f
            zoomScale < 2.3f -> 2f
            else -> 2.5f
        }
        val targetWidth = (requestedWidthPx * qualityScale)
            .toInt()
            .coerceIn(1, MAX_PDF_RENDER_WIDTH)
        val targetHeight = if (requestedHeightPx > 0) {
            (requestedHeightPx * qualityScale)
                .toInt()
                .coerceIn(1, MAX_PDF_BITMAP_DIMENSION)
        } else {
            0
        }
        val cachedBitmap = state.pdfPageBitmaps[page]
        if (cachedBitmap != null) {
            val requiredWidth = if (targetHeight > 0) {
                minOf(
                    targetWidth.toFloat(),
                    targetHeight.toFloat() * cachedBitmap.width / cachedBitmap.height,
                )
            } else {
                targetWidth.toFloat()
            }
            if (cachedBitmap.width >= requiredWidth * 0.97f) return
        }
        val bookFile = File(book.filePath)
        val cropWhitespace = state.settings.cropPdfWhitespace
        // Width is part of the cache identity so a landscape render can never
        // reuse a narrow portrait bitmap. The bucket keeps the key stable
        // across small inset changes while rotation still produces a new key.
        // The crop flag also separates trimmed and untrimmed renders.
        val widthBucket = (targetWidth / PDF_CACHE_WIDTH_BUCKET_PX).coerceAtLeast(1)
        val cacheBookKey =
            "${book.id}:${bookFile.absolutePath}:${bookFile.length()}:${bookFile.lastModified()}:$targetHeight:$widthBucket:crop=$cropWhitespace"
        val renderKey = "$cacheBookKey:$page:$targetWidth"
        synchronized(stateLock) {
            // A previous request may still be in flight. The per-page UI
            // retries after a short delay, and the job always removes this key
            // in `finally`, so a cancelled/failed render can never wedge the
            // page on a permanent spinner.
            if (!renderingPages.add(renderKey)) return
        }

        guardedLaunch {
            var shouldPersistToDisk = false
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    PdfPageCache.get(appContext, cacheBookKey, page, targetWidth)?.let { cached ->
                        return@withContext cached
                    }
                    val renderedBitmap = pdfRenderMutex.withLock {
                        val renderer = synchronized(stateLock) {
                            if (pdfRendererBookId != book.id) {
                                throw IOException("PDF renderer is not open for this book.")
                            }
                            pdfRenderer ?: throw IOException("PDF renderer is closed.")
                        }
                        val pdfPage = renderer.openPage(page)
                        try {
                            val rendered = renderPageCrisp(
                                page = pdfPage,
                                targetWidth = targetWidth,
                                targetHeight = targetHeight,
                                zoomScale = 1f,
                                maxBitmapPixels = maxPdfBitmapPixels(),
                                maxBitmapDimension = MAX_PDF_BITMAP_DIMENSION,
                            ) ?: throw IOException("Not enough memory to render this PDF page.")
                            if (cropWhitespace) {
                                // Cropping is best-effort: a failed bounds scan
                                // returns the original rendered bitmap instead
                                // of null, so the page still paints.
                                cropPdfWhitespaceMargins(rendered)
                            } else {
                                rendered
                            }
                        } finally {
                            pdfPage.close()
                        }
                    }
                    shouldPersistToDisk = true
                    PdfPageCache.putMemory(cacheBookKey, page, renderedBitmap)
                    renderedBitmap
                }
                val current = _uiState.value
                if (
                    current.book?.id == book.id &&
                    current.settings.cropPdfWhitespace == cropWhitespace
                ) {
                    val preferredBitmap = listOfNotNull(
                        current.pdfPageBitmaps[page],
                        bitmap,
                    ).maxBy { it.width }
                    val updatedBitmaps = retainNearbyPdfPages(
                        current.pdfPageBitmaps + (page to preferredBitmap),
                        current.pdfPageIndex,
                    )
                    val selectedBitmap = updatedBitmaps[current.pdfPageIndex]
                    _uiState.value = current.copy(
                        pdfPageBitmap = selectedBitmap,
                        pdfPageBitmaps = updatedBitmaps,
                    )
                }
                if (shouldPersistToDisk) {
                    guardedLaunch(Dispatchers.IO) {
                        PdfPageCache.putDisk(appContext, cacheBookKey, page, targetWidth, bitmap)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (_uiState.value.book?.id == book.id) {
                    _uiState.value = _uiState.value.copy(
                        message = error.localizedMessage ?: "Could not render this PDF page.",
                    )
                }
            } finally {
                synchronized(stateLock) {
                    renderingPages.remove(renderKey)
                }
            }
        }
    }

    private fun retainNearbyPdfPages(bitmaps: Map<Int, Bitmap>, selectedPage: Int): Map<Int, Bitmap> =
        bitmaps.entries
            .sortedBy { (page, _) -> kotlin.math.abs(page - selectedPage) }
            .take(MAX_CACHED_PDF_PAGES)
            .associate { it.key to it.value }

    fun updateEpubProgress(chapterIndex: Int, scrollOffsetPx: Int, progress: Float) {
        val chapter = chapterIndex.coerceIn(0, (_uiState.value.chapters.size - 1).coerceAtLeast(0))
        currentEpubChapter = chapter
        persistProgress("epub:$chapter:${scrollOffsetPx.coerceAtLeast(0)}", progress.coerceIn(0f, 1f))
    }

    fun saveSelectedQuote() {
        val selection = _uiState.value.selection ?: return
        guardedLaunch {
            setWorking(true)
            try {
                val text = sanitizeStoredText(selection.text)
                if (text.isEmpty()) {
                    showMessage("This selection is empty and cannot be saved as a quote.")
                    return@guardedLaunch
                }
                database.quoteDao().upsert(
                    QuoteEntity(
                        bookId = _uiState.value.book?.id
                            ?: throw IOException("Open a book before saving a quote."),
                        selectedText = text,
                        chapter = selection.chapterTitle.take(MAX_META_TEXT_CHARS),
                        chapterIndex = selection.chapterIndex.coerceAtLeast(0),
                        characterOffset = selection.startOffset.coerceAtLeast(0),
                        endCharacterOffset = selection.endOffset.coerceAtLeast(0),
                        source = QuoteSource.USER,
                    ),
                )
                clearSelection()
                showMessage("Quote saved.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showFailureMessage(error)
            } finally {
                setWorking(false)
            }
        }
    }

    fun saveHighlight() {
        val selection = _uiState.value.selection ?: return
        if (selection.highlightId != null) {
            showMessage("This passage is already highlighted.")
            return
        }
        guardedLaunch {
            setWorking(true)
            try {
                val book = _uiState.value.book
                    ?: throw IOException("Open a book before highlighting.")
                val entity = withContext(Dispatchers.IO) {
                    buildSanitizedHighlight(selection, book)
                }
                if (entity == null) {
                    showMessage("This selection is empty and cannot be highlighted.")
                    return@guardedLaunch
                }
                database.highlightDao().upsert(entity)
                clearSelection()
                showMessage("Highlight saved.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showFailureMessage(error)
            } finally {
                setWorking(false)
            }
        }
    }

    /**
     * Builds a database-safe highlight row: trims and caps oversized text
     * (prevents SQLiteBlobTooBigException), drops non-finite/out-of-range
     * highlight rectangles, and bounds every stored field. Returns null when
     * nothing meaningful is left after sanitizing.
     */
    private fun buildSanitizedHighlight(
        selection: ReaderSelection,
        book: BookEntity,
    ): HighlightEntity? {
        val text = sanitizeStoredText(selection.text)
        if (text.isEmpty()) return null
        val isPdf = book.format.equals("PDF", ignoreCase = true)
        val safeRects = selection.pdfHighlightRects
            .filter(PdfSelectionRect::isValid)
            .take(MAX_HIGHLIGHT_RECTS)
        val note = selection.chapterTitle.trim().take(MAX_META_TEXT_CHARS).ifBlank { null }
        val start = selection.startOffset.coerceAtLeast(0)
        val end = selection.endOffset.coerceAtLeast(start)
        return HighlightEntity(
            bookId = book.id,
            selectedText = text,
            chapterIndex = selection.chapterIndex.coerceAtLeast(0),
            characterOffset = start,
            endCharacterOffset = end,
            startOffset = start,
            endOffset = end,
            note = note,
            pageIndex = selection.chapterIndex.takeIf { isPdf },
            startCharOffset = start,
            endCharOffset = end,
            colorHex = "#FFFF00",
            boundsJson = safeRects
                .takeIf { it.isNotEmpty() }
                ?.let(::encodePdfHighlightBounds),
        )
    }

    /** Trims and caps stored text so oversized rows cannot crash SQLite. */
    private fun sanitizeStoredText(text: String): String =
        text.trim().take(MAX_STORED_TEXT_CHARS).trim()

    fun deleteHighlight(highlight: com.example.vibereader.data.room.HighlightEntity) {
        guardedLaunch {
            try {
                database.highlightDao().delete(highlight)
                showMessage("Highlight removed.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not remove this highlight.")
            }
        }
    }

    fun askBookAi(question: String) {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) return
        val book = _uiState.value.book ?: return
        if (trimmedQuestion == ENTIRE_BOOK_SUMMARY_COMMAND) {
            summarizeEntireBook(book)
            return
        }
        // A synchronous guard prevents double-taps from starting two requests
        // whose finally blocks could unlock "Working…" while the other is live.
        if (_uiState.value.isWorking) return
        setWorking(true)
        _uiState.value = _uiState.value.copy(aiResult = null)
        guardedLaunch(Dispatchers.IO) {
            try {
                // Ask Book must work even when nothing is selected and no page
                // text has loaded yet. The engine tries a zero-context
                // title/author answer first, then falls back to locally scored
                // paragraphs. The assistant sheet's scope is not passed through
                // its callback, so the question wording decides whether the
                // current passage is required.
                val askContext = resolveAskAiContext(book)
                val response = withTimeout(MAX_AI_OPERATION_MS) {
                    askAiEngine.askBook(
                        context = AskAiEngine.BookContext(
                            bookTitle = book.title,
                            author = book.author,
                            contextText = askContext.text,
                            bookId = book.id,
                            bookChapters = _uiState.value.chapters.map { it.text },
                            contextIsExcerpt = askContext.isExcerpt,
                        ),
                        question = trimmedQuestion,
                    )
                }
                if (_uiState.value.book?.id == book.id) {
                    _uiState.value = _uiState.value.copy(
                        aiResult = AiTaskResult("Ask Book AI", response),
                    )
                }
            } catch (timeout: TimeoutCancellationException) {
                Log.e(AI_ERROR_TAG, "Execution error", timeout)
                showAiError(
                    book = book,
                    title = "Ask Book AI failed",
                    message = AI_TIMEOUT_MESSAGE,
                    retry = { askBookAi(trimmedQuestion) },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(AI_ERROR_TAG, "Execution error", error)
                showAiError(
                    book = book,
                    title = "Ask Book AI failed",
                    message = error.toAiUiMessage("Could not answer this question."),
                    retry = { askBookAi(trimmedQuestion) },
                )
            } finally {
                setWorking(false)
            }
        }
    }

    /**
     * Publishes a visible error card in the Book Assistant sheet, keeps the
     * transient Snackbar for parity with other reader actions, and force-emits a
     * Toast so the user never sees a silent AI failure.
     */
    private fun showAiError(
        book: BookEntity,
        title: String,
        message: String,
        retry: (() -> Unit)? = null,
    ) {
        val clean = message.trim().ifBlank { AiFailoverManager.ALL_KEYS_FAILED_MESSAGE }
        Log.e(AI_ERROR_TAG, "Execution error: $title - $clean")
        if (_uiState.value.book?.id != book.id) return
        _uiState.value = _uiState.value.copy(
            aiResult = AiTaskResult(
                title = title,
                content = clean,
                isError = true,
                retry = retry,
            ),
        )
        showMessage(clean)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "AI Error: $clean", Toast.LENGTH_LONG).show()
        }
    }

    private fun Throwable.toAiUiMessage(fallback: String): String = when (this) {
        is AiConfigurationException ->
            localizedMessage?.takeIf(String::isNotBlank)
                ?: AiRuntimeResolver.SETUP_ERROR_MESSAGE

        is AiRequestException ->
            localizedMessage?.takeIf(String::isNotBlank)
                ?: AiFailoverManager.ALL_KEYS_FAILED_MESSAGE

        else ->
            "AI Error: " + (
                localizedMessage?.takeIf(String::isNotBlank)
                    ?: message?.takeIf(String::isNotBlank)
                    ?: fallback
                )
    }

    /**
     * Builds the exact context sent with an Ask AI question. Priority:
     * selected text, the visible PDF page, then the chapter the reader is
     * actually on (not merely the initially restored chapter).
     *
     * [AskAiContext.isExcerpt] tells the engine that the text is already the
     * exact passage and does not need paragraph scoring. Chapter text is scored
     * locally instead of being sent whole.
     */
    private suspend fun resolveAskAiContext(book: BookEntity): AskAiContext {
        val state = _uiState.value
        state.selection?.text
            ?.takeIf(String::isNotBlank)
            ?.let { return AskAiContext(it.take(MAX_ASK_CONTEXT_CHARS), isExcerpt = true) }

        if (book.format.equals("PDF", ignoreCase = true)) {
            state.pdfPageText
                .takeIf(String::isNotBlank)
                ?.let { return AskAiContext(it.take(MAX_ASK_CONTEXT_CHARS), isExcerpt = true) }

            // Scanned/cover pages can have no selectable layer. Walk the
            // current page and its nearest neighbours before giving up so Ask
            // AI still receives real context from large PDFs.
            val currentPage = state.pdfPageIndex.coerceAtLeast(0)
            val pageOffsets = listOf(0, 1, -1, 2, -2)
            for (offset in pageOffsets) {
                val page = currentPage + offset
                if (page < 0) continue
                if (state.pdfPageCount > 0 && page >= state.pdfPageCount) continue
                val text = runCatching { extractPdfPageText(book, page) }
                    .getOrDefault("")
                    .trim()
                if (text.isNotBlank()) {
                    return AskAiContext(
                        text = text.take(MAX_ASK_CONTEXT_CHARS),
                        isExcerpt = true,
                    )
                }
            }
            return AskAiContext("", isExcerpt = true)
        }

        val chapters = state.chapters
        if (chapters.isEmpty()) return AskAiContext("", isExcerpt = false)
        val chapterIndex = currentEpubChapter.coerceIn(0, chapters.size - 1)
        return AskAiContext(
            text = chapters[chapterIndex].text
                .takeIf(String::isNotBlank)
                ?.take(MAX_ASK_CONTEXT_CHARS)
                .orEmpty(),
            isExcerpt = false,
        )
    }

    private data class AskAiContext(
        val text: String,
        val isExcerpt: Boolean,
    )

    fun extractMinimalistQuote() {
        val selection = _uiState.value.selection ?: return
        val book = _uiState.value.book ?: return
        runAiTask(AiTask.EXTRACT_QUOTE, selection.text, book, selection.chapterTitle, selection)
    }

    fun summarizeSelection() {
        val selection = _uiState.value.selection ?: return
        val book = _uiState.value.book ?: return
        runAiTask(AiTask.SUMMARIZE, selection.text, book, selection.chapterTitle)
    }

    fun explainSelection() {
        val selection = _uiState.value.selection ?: return
        val book = _uiState.value.book ?: return
        runAiTask(AiTask.EXPLAIN, selection.text, book, selection.chapterTitle)
    }

    fun summarizeChapter(chapterIndex: Int) {
        val state = _uiState.value
        val chapter = state.chapters.getOrNull(chapterIndex) ?: return
        val book = state.book ?: return
        currentEpubChapter = chapterIndex.coerceAtLeast(0)
        runAiTask(AiTask.SUMMARIZE, chapter.text, book, chapter.title)
    }

    /**
     * Entire-book summary: only the title and author are sent. The engine asks
     * the model's online knowledge and returns a clean message when the book is
     * unknown, so no chapter text is ever uploaded for this action.
     */
    fun summarizeEntireBook() {
        val book = _uiState.value.book ?: return
        summarizeEntireBook(book)
    }

    private fun summarizeEntireBook(book: BookEntity) {
        if (_uiState.value.isWorking) return
        setWorking(true)
        _uiState.value = _uiState.value.copy(aiResult = null)
        guardedLaunch(Dispatchers.IO) {
            try {
                val response = withTimeout(MAX_AI_OPERATION_MS) {
                    askAiEngine.summarizeEntireBook(
                        bookTitle = book.title,
                        author = book.author,
                        bookId = book.id,
                    )
                }.usableAiTextOrNull()
                    ?: throw IOException("The AI provider returned an empty book summary.")
                if (_uiState.value.book?.id == book.id) {
                    _uiState.value = _uiState.value.copy(
                        aiResult = AiTaskResult("Summary · Entire book", response),
                    )
                }
            } catch (timeout: TimeoutCancellationException) {
                Log.e(AI_ERROR_TAG, "Execution error", timeout)
                showAiError(
                    book = book,
                    title = "Summary failed",
                    message = AI_TIMEOUT_MESSAGE,
                    retry = { summarizeEntireBook(book) },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(AI_ERROR_TAG, "Execution error", error)
                showAiError(
                    book = book,
                    title = "Summary failed",
                    message = error.toAiUiMessage("Could not summarize this book."),
                    retry = { summarizeEntireBook(book) },
                )
            } finally {
                setWorking(false)
            }
        }
    }

    fun summarizePdfPage(pageIndex: Int) {
        val state = _uiState.value
        val book = state.book?.takeIf { it.format.equals("PDF", ignoreCase = true) } ?: return
        if (pageIndex !in 0 until state.pdfPageCount) return
        guardedLaunch {
            try {
                val pageText = withContext(Dispatchers.IO) {
                    extractPdfPageText(book, pageIndex)
                }
                if (pageText.isBlank()) {
                    showMessage("No text could be extracted from this PDF page.")
                } else {
                    runAiTask(
                        AiTask.SUMMARIZE,
                        pageText,
                        book,
                        "Page ${pageIndex + 1}",
                        processingPageIndex = pageIndex,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not read this PDF page.")
            }
        }
    }

    fun readPdfPageText(pageIndex: Int, onTextReady: (String) -> Unit) {
        val state = _uiState.value
        val book = state.book?.takeIf { it.format.equals("PDF", ignoreCase = true) } ?: return
        if (pageIndex !in 0 until state.pdfPageCount) return
        guardedLaunch {
            try {
                val layout = withContext(Dispatchers.IO) {
                    loadPdfPageLayout(book, pageIndex)
                }
                if (layout.text.isBlank()) {
                    showMessage("No readable text was found on this PDF page.")
                } else {
                    onTextReady(layout.text)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not read this PDF page.")
            }
        }
    }

    fun dismissAiResult() {
        _uiState.value = _uiState.value.copy(aiResult = null)
    }

    private suspend fun extractPdfPageText(book: BookEntity, pageIndex: Int): String =
        withContext(Dispatchers.IO) {
            loadPdfPageLayout(book, pageIndex).text
        }

    /**
     * Returns a cached PDFBox layout for [pageIndex]. Building the layout is
     * expensive, so selection drags and TTS reuse the same word/character data.
     *
     * Race protection: concurrent callers for the same page (selection drag +
     * page text + TTS can all arrive at once) share one extraction via a
     * per-page gate, so at most one PDDocument is loaded at a time. Extraction
     * itself never runs while holding [pdfLayoutLock], so the main thread can
     * keep taking fast cache reads without ANR risk.
     */
    private fun loadPdfPageLayout(book: BookEntity, pageIndex: Int): PdfPageLayout {
        while (true) {
            synchronized(pdfLayoutLock) { pdfPageLayouts[pageIndex] }?.let { return it }

            val inFlight = synchronized(pdfLayoutGateLock) { pdfLayoutGates[pageIndex] }
            if (inFlight != null) {
                // Wait for the running extraction to finish, then re-check cache.
                synchronized(inFlight) {
                    (inFlight as java.lang.Object).wait(PDF_LAYOUT_WAIT_TIMEOUT_MS)
                }
                continue
            }

            val gate = Any()
            val claimed = synchronized(pdfLayoutGateLock) {
                if (pdfLayoutGates.contains(pageIndex)) {
                    false
                } else {
                    pdfLayoutGates[pageIndex] = gate
                    true
                }
            }
            if (!claimed) continue
            try {
                val layout = extractPdfPageLayoutSafely(book, pageIndex)
                if (_uiState.value.book?.id == book.id) {
                    synchronized(pdfLayoutLock) {
                        pdfPageLayouts[pageIndex] = layout
                        while (pdfPageLayouts.size > MAX_CACHED_PDF_PAGES) {
                            val oldest = pdfPageLayouts.keys.firstOrNull() ?: break
                            pdfPageLayouts.remove(oldest)
                        }
                    }
                }
                return layout
            } finally {
                synchronized(pdfLayoutGateLock) { pdfLayoutGates.remove(pageIndex) }
                synchronized(gate) { (gate as java.lang.Object).notifyAll() }
            }
        }
    }

    /**
     * Extracts one page, converting every failure — including Errors such as
     * OutOfMemoryError that are common with hostile PDFs — into a friendly
     * IOException the callers already handle by showing a Snackbar.
     */
    private fun extractPdfPageLayoutSafely(book: BookEntity, pageIndex: Int): PdfPageLayout =
        try {
            extractPdfPageLayout(book, pageIndex)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(TAG, "PDF page extraction failed (book=${book.id}, page=$pageIndex)", failure)
            throw toFriendlyPdfError(failure)
        }

    private fun toFriendlyPdfError(error: Throwable): IOException = when (error) {
        is OutOfMemoryError ->
            IOException("Not enough memory to process this PDF page.", error)
        is IllegalArgumentException, is IllegalStateException ->
            IOException("This PDF page contains corrupt layout data.", error)
        is IOException ->
            error.localizedMessage
                ?.takeIf(String::isNotBlank)
                ?.let { IOException(it, error) }
                ?: IOException("This PDF page could not be read.", error)
        else ->
            IOException(
                error.localizedMessage?.takeIf(String::isNotBlank)
                    ?: "This PDF page could not be read.",
                error,
            )
    }

    private fun extractPdfPageLayout(book: BookEntity, pageIndex: Int): PdfPageLayout {
        val file = File(book.filePath)
        if (!file.isFile) throw IOException("The local PDF file is missing.")
        PDFBoxResourceLoader.init(appContext)
        return PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            if (pageIndex !in 0 until document.numberOfPages) {
                throw IOException("The requested PDF page is unavailable.")
            }
            val page = document.getPage(pageIndex)
            val mediaBox = page.mediaBox
                ?: throw IOException("The requested PDF page has invalid bounds.")
            val pageWidth = mediaBox.width
            val pageHeight = mediaBox.height
            if (
                !pageWidth.isFinite() || !pageHeight.isFinite() ||
                pageWidth <= 0f || pageHeight <= 0f
            ) {
                throw IOException("The requested PDF page has invalid bounds.")
            }

            val positions = mutableListOf<TextPosition>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(
                    text: String,
                    textPositions: MutableList<TextPosition>,
                ) {
                    super.writeString(text, textPositions)
                    positions.addAll(textPositions)
                }
            }.apply {
                startPage = pageIndex + 1
                endPage = pageIndex + 1
            }
            val text = stripper.getText(document).trim()
            // Guard against pathological pages whose glyph data would allocate
            // a rect list large enough to OOM-crash the app.
            if (text.length > MAX_PDF_PAGE_TEXT_CHARS) {
                throw IOException("This PDF page is too large to process.")
            }
            val characterRects = MutableList<PdfSelectionRect?>(text.length) { null }
            val glyphs = mutableListOf<PdfGlyph>()
            var searchFrom = 0
            positions.forEach { position ->
                val unicode = position.unicode.orEmpty()
                if (unicode.isEmpty()) return@forEach
                val match = text.indexOf(unicode, searchFrom)
                if (match < 0) return@forEach
                val rect = PdfSelectionRect(
                    left = (position.xDirAdj / pageWidth).coerceIn(0f, 1f),
                    top = ((position.yDirAdj - position.heightDir) / pageHeight)
                        .coerceIn(0f, 1f),
                    right = ((position.xDirAdj + position.widthDirAdj) / pageWidth)
                        .coerceIn(0f, 1f),
                    bottom = (position.yDirAdj / pageHeight).coerceIn(0f, 1f),
                )
                if (rect.isValid) {
                    repeat(unicode.length) { offset ->
                        val charIndex = match + offset
                        if (charIndex in characterRects.indices) {
                            characterRects[charIndex] = rect
                        }
                    }
                    glyphs += PdfGlyph(
                        text = unicode,
                        rect = rect,
                        charStart = match,
                        charEnd = match + unicode.length,
                    )
                }
                searchFrom = match + unicode.length
            }

            PdfPageLayout(
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                text = text,
                charRects = characterRects,
                words = PdfWordBuilder.build(glyphs),
            )
        }
    }

    private fun runAiTask(
        task: AiTask,
        text: String,
        book: BookEntity,
        chapterTitle: String,
        selection: ReaderSelection? = null,
        processingPageIndex: Int? = null,
    ) {
        if (text.isBlank()) {
            showMessage("There is no readable text to send to the AI assistant.")
            return
        }
        if (_uiState.value.isWorking) return
        if (processingPageIndex != null) {
            _uiState.value = _uiState.value.copy(aiProcessingPageIndex = processingPageIndex)
        }
        setWorking(true)
        _uiState.value = _uiState.value.copy(aiResult = null)
        guardedLaunch(Dispatchers.IO) {
            try {
                val response = withTimeout(MAX_AI_OPERATION_MS) {
                    requestAiTaskForLongText(
                        task = task,
                        text = text,
                        bookId = book.id,
                    )
                }
                if (_uiState.value.book?.id == book.id) {
                    if (task == AiTask.EXTRACT_QUOTE && selection != null) {
                        val (header, quote) = runCatching { parseQuote(response) }
                            .getOrElse {
                                // Keep quote extraction useful even when a free
                                // model ignores the JSON contract. The selected
                                // passage is already faithful book text.
                                val fallback = AiContextSanitizer.truncateAtBoundary(
                                    AiContextSanitizer.sanitize(selection.text),
                                    MAX_READER_QUOTE_CHARS,
                                ).trim()
                                if (fallback.isBlank()) throw it
                                "QUOTE" to fallback
                            }
                        val storedText = sanitizeStoredText("$header\n\n$quote")
                        val duplicate = database.quoteDao()
                            .getForBook(book.id)
                            .any {
                                it.selectedText.trim().equals(storedText.trim(), ignoreCase = true)
                            }
                        if (duplicate) {
                            showMessage("That AI quote is already in your library.")
                        } else {
                            database.quoteDao().upsert(
                                QuoteEntity(
                                    bookId = book.id,
                                    selectedText = storedText,
                                    chapter = chapterTitle.take(MAX_META_TEXT_CHARS),
                                    chapterIndex = selection.chapterIndex.coerceAtLeast(0),
                                    characterOffset = selection.startOffset.coerceAtLeast(0),
                                    endCharacterOffset = selection.endOffset.coerceAtLeast(0),
                                    source = QuoteSource.AI,
                                ),
                            )
                            showMessage("AI quote saved.")
                        }
                        clearSelection()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            aiResult = AiTaskResult(
                                "${task.title} · $chapterTitle",
                                response,
                            ),
                        )
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                Log.e(AI_ERROR_TAG, "Execution error", timeout)
                showAiError(
                    book = book,
                    title = "${task.title} failed",
                    message = AI_TIMEOUT_MESSAGE,
                    retry = {
                        runAiTask(
                            task = task,
                            text = text,
                            book = book,
                            chapterTitle = chapterTitle,
                            selection = selection,
                            processingPageIndex = processingPageIndex,
                        )
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(AI_ERROR_TAG, "Execution error", error)
                showAiError(
                    book = book,
                    title = "${task.title} failed",
                    message = error.toAiUiMessage(
                        "AI ${task.title.lowercase()} failed. Please try again.",
                    ),
                    retry = {
                        runAiTask(
                            task = task,
                            text = text,
                            book = book,
                            chapterTitle = chapterTitle,
                            selection = selection,
                            processingPageIndex = processingPageIndex,
                        )
                    },
                )
            } finally {
                setWorking(false)
                if (processingPageIndex != null) {
                    _uiState.value = _uiState.value.copy(aiProcessingPageIndex = null)
                }
            }
        }
    }

    private suspend fun openPdf(book: BookEntity): Int = withContext(Dispatchers.IO) {
        val file = File(book.filePath)
        if (!file.isFile) throw IOException("The local PDF file is missing.")
        synchronized(stateLock) {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(descriptor)
                pdfDescriptor = descriptor
                pdfRenderer = renderer
                pdfRendererBookId = book.id
                renderer.pageCount
            } catch (error: Exception) {
                descriptor.close()
                throw error
            }
        }
    }

    private fun persistProgress(location: String, progress: Float) {
        val bookId = _uiState.value.book?.id ?: return
        pendingProgress = Triple(bookId, location, progress)
        progressJob?.cancel()
        progressJob = guardedLaunch {
            kotlinx.coroutines.delay(PROGRESS_SAVE_DELAY_MS)
            try {
                database.readingProgressDao().upsert(
                    ReadingProgressEntity(bookId, location, progress),
                )
                bookRepository.updateLastReadTimestamp(bookId, System.currentTimeMillis())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not save reading progress.")
            }
        }
    }

    private fun setWorking(isWorking: Boolean) {
        _uiState.value = _uiState.value.copy(isWorking = isWorking)
    }

    private fun splitAiText(text: String, maxChunkChars: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxChunkChars).coerceAtMost(text.length)
            if (end < text.length) {
                val minimumBoundary = start + maxChunkChars / 2
                val paragraphBreak = text.lastIndexOf("\n\n", end)
                    .takeIf { it > minimumBoundary }
                val sentenceBreak = text.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), end)
                    .takeIf { it > minimumBoundary }
                val wordBreak = text.lastIndexOf(' ', end)
                    .takeIf { it > minimumBoundary }
                end = paragraphBreak?.plus(2)
                    ?: sentenceBreak?.plus(1)
                    ?: wordBreak?.plus(1)
                    ?: end
            }
            val chunk = text.substring(start, end).trim()
            if (chunk.isNotEmpty()) chunks += chunk
            start = end
        }
        return chunks
    }

    private suspend fun requestAiTaskForLongText(
        task: AiTask,
        text: String,
        bookId: String,
    ): String {
        val cleanText = AiContextSanitizer.sanitize(text)
        if (cleanText.isBlank()) {
            throw IOException("There is no readable text to send to the AI assistant.")
        }
        val fullCacheHash = AiResponseCache.hash(task.name, cleanText)
        aiResponseCache.get(bookId, AiResponseCache.KIND_READER_TASK, fullCacheHash)
            ?.usableAiTextOrNull()
            ?.let { return it }

        val result = if (task == AiTask.EXTRACT_QUOTE || cleanText.length <= MAX_AI_CHUNK_CHARS) {
            requestAiTask(task, cleanText, bookId)
        } else {
            val chunks = splitAiText(cleanText, MAX_AI_CHUNK_CHARS)
            val partialResults = chunks.mapIndexed { index, chunk ->
                requestAiTask(task, chunk, bookId).let { partial ->
                    if (task == AiTask.SUMMARIZE) "Part ${index + 1}: $partial" else partial
                }
            }
            if (task == AiTask.SUMMARIZE && partialResults.size > 1) {
                requestAiTask(
                    task = AiTask.SUMMARIZE,
                    selectedText = partialResults.joinToString("\n\n"),
                    bookId = bookId,
                )
            } else {
                partialResults.joinToString(separator = "\n\n")
            }
        }
        val usableResult = result.usableAiTextOrNull()
            ?: throw IOException("The AI provider returned an empty ${task.title.lowercase()}.")
        aiResponseCache.put(
            bookId = bookId,
            cacheKind = AiResponseCache.KIND_READER_TASK,
            queryHash = fullCacheHash,
            content = usableResult,
        )
        return usableResult
    }

    /**
     * Runs one chunk through [AskAiEngine], which resolves the provider/key/model
     * failover pool on every request and transparently tries the next saved
     * provider on 401/429/network errors before surfacing a single UI error.
     *
     * Repeated summaries of the same page/chapter/selection are served from
     * Room, so they consume zero provider tokens.
     */
    private suspend fun requestAiTask(
        task: AiTask,
        selectedText: String,
        bookId: String,
    ): String {
        val cleanText = AiContextSanitizer.sanitize(selectedText)
        if (cleanText.isBlank()) {
            throw IOException("There is no readable text to send to the AI assistant.")
        }
        val cacheHash = AiResponseCache.hash(task.name, cleanText)
        aiResponseCache.get(bookId, AiResponseCache.KIND_READER_TASK, cacheHash)
            ?.usableAiTextOrNull()
            ?.let { return it }
        val response = askAiEngine.runTask(task.toReaderTask(), cleanText)
            .usableAiTextOrNull()
            ?: throw IOException("The AI provider returned an empty ${task.title.lowercase()}.")
        aiResponseCache.put(
            bookId = bookId,
            cacheKind = AiResponseCache.KIND_READER_TASK,
            queryHash = cacheHash,
            content = response,
        )
        return response
    }

    private fun String.usableAiTextOrNull(): String? =
        AiResponseSanitizer.sanitize(this).trim().takeIf {
            it.isNotEmpty() &&
                !it.equals("null", ignoreCase = true) &&
                !it.equals("undefined", ignoreCase = true)
        }

    private fun AiTask.toReaderTask(): AskAiEngine.Task = when (this) {
        AiTask.SUMMARIZE -> AskAiEngine.Task.SUMMARIZE
        AiTask.EXPLAIN -> AskAiEngine.Task.EXPLAIN
        AiTask.EXTRACT_QUOTE -> AskAiEngine.Task.EXTRACT_QUOTE
    }

    /**
     * Parses the strict JSON quote response without being defeated by a fence,
     * a short preamble, a trailing comma, or lowercase header text. When the
     * model emits malformed JSON, field-level recovery still extracts the two
     * values instead of showing "invalid quote format".
     */
    private fun parseQuote(response: String): Pair<String, String> {
        val cleaned = AiResponseSanitizer.sanitize(
            response,
            extractFencedPayload = true,
            stripChatter = false,
        )
        val jsonText = extractJsonObject(cleaned) ?: cleaned
        val result = runCatching { JSONObject(jsonText) }.getOrNull()

        val headerRaw = result?.optString("header").orEmpty().ifBlank {
            HEADER_FIELD_REGEX.find(cleaned)?.groupValues?.getOrNull(1).orEmpty()
        }
        val quoteRaw = result?.optString("quote").orEmpty().ifBlank {
            QUOTE_FIELD_REGEX.find(cleaned)?.groupValues?.getOrNull(1).orEmpty()
        }

        val header = headerRaw
            .uppercase(Locale.US)
            .replace(NON_HEADER_CHARS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_QUOTE_HEADER_CHARS)
            .ifBlank { "QUOTE" }
        val quote = AiResponseSanitizer
            .sanitize(quoteRaw, stripChatter = false)
            .replace(WHITESPACE, " ")
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’', ',', ';')

        if (
            quote.isBlank() ||
            quote.length > MAX_READER_QUOTE_CHARS ||
            AiResponseSanitizer.isArtifact(quote) ||
            !QuoteQualityValidator.isValidQuote(quote)
        ) {
            throw IOException("AI returned an invalid quote format. Please try again.")
        }
        return header to quote
    }

    /**
     * Extracts the first balanced JSON object. Braces inside quoted strings are
     * ignored, so a quote containing `{` or `}` cannot truncate the payload.
     */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private suspend fun closePdfRenderer() {
        // Wait for any in-flight native render to release the renderer before
        // closing it; otherwise the native call can race with teardown.
        pdfRenderMutex.withLock {
            synchronized(stateLock) {
                renderingPages.clear()
                pdfRenderer?.close()
                pdfRenderer = null
                pdfRendererBookId = null
                pdfDescriptor?.close()
                pdfDescriptor = null
            }
        }
    }

    override fun onCleared() {
        val pending = pendingProgress
        // Native renderer teardown and the final progress write can outlast a
        // frame. Run both off the main thread so leaving the reader never
        // stalls the exit transition.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { closePdfRenderer() }
            if (pending != null) {
                runCatching {
                    database.readingProgressDao().upsert(
                        ReadingProgressEntity(pending.first, pending.second, pending.third),
                    )
                    bookRepository.updateLastReadTimestamp(
                        pending.first,
                        System.currentTimeMillis(),
                    )
                }
            }
        }
        super.onCleared()
    }

    class Factory(
        private val context: Context,
        private val database: VibeReaderDatabase,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
                throw IllegalArgumentException("Unsupported ViewModel type: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return ReaderViewModel(context, database) as T
        }
    }

    private companion object {
        const val TAG = "ReaderViewModel"
        const val AI_ERROR_TAG = "VibeReader_AI"
        const val MAX_AI_CHUNK_CHARS = 16_000
        const val MAX_ASK_CONTEXT_CHARS = 12_000

        /**
         * Hard ceiling for a whole AI action, including chunked summaries.
         * Each chunk gets its own 90-second failover budget; the outer ceiling
         * must leave room for several chunks instead of cancelling halfway.
         */
        const val MAX_AI_OPERATION_MS = 300_000L
        const val AI_TIMEOUT_MESSAGE =
            "The AI request timed out. Check your internet connection and try again."
        const val MAX_STORED_TEXT_CHARS = 100_000
        const val MAX_META_TEXT_CHARS = 240
        const val MAX_HIGHLIGHT_RECTS = 2000
        const val MAX_PDF_RENDER_WIDTH = 3072
        const val MAX_PDF_BITMAP_PIXELS = 12_000_000L
        const val MAX_PDF_BITMAP_DIMENSION = 8192
        const val MAX_CACHED_PDF_PAGES = 3
        const val PDF_CACHE_WIDTH_BUCKET_PX = 256
        const val MAX_PDF_PAGE_TEXT_CHARS = 1_000_000
        const val PDF_LAYOUT_WAIT_TIMEOUT_MS = 250L
        const val PROGRESS_SAVE_DELAY_MS = 500L
        const val OPEN_DEFERRAL_MS = 180L
        const val READER_PREFERENCES_NAME = "reader_settings"
        const val KEY_CROP_PDF_WHITESPACE = "crop_pdf_whitespace"
        val EPUB_LOCATION_REGEX = Regex("""epub:(\d+):(\d+)""")
        const val MAX_QUOTE_HEADER_CHARS = 24
        const val MAX_READER_QUOTE_CHARS = 1_200
        val WHITESPACE = Regex("""\s+""")
        val NON_HEADER_CHARS = Regex("[^A-Za-z0-9 -]")
        val HEADER_FIELD_REGEX = Regex(
            "(?i)\"header\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
        )
        val QUOTE_FIELD_REGEX = Regex(
            "(?i)\"quote\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
        )
    }

    private fun maxPdfBitmapPixels(): Long =
        minOf(MAX_PDF_BITMAP_PIXELS, (Runtime.getRuntime().maxMemory() / 64L).coerceAtLeast(1L))

    private fun encodePdfHighlightBounds(rects: List<PdfSelectionRect>): String =
        org.json.JSONArray().apply {
            rects.forEach { rect ->
                put(
                    org.json.JSONArray()
                        .put(rect.left)
                        .put(rect.top)
                        .put(rect.right)
                        .put(rect.bottom),
                )
            }
        }.toString()

    private enum class AiTask(val title: String) {
        SUMMARIZE("Summary"),
        EXPLAIN("Explanation"),
        EXTRACT_QUOTE("Quote extraction"),
    }

}

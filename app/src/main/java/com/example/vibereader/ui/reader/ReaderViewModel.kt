package com.example.vibereader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.core.secure_storage.ApiKeyStore
import com.example.vibereader.data.repository.BookParserRepository
import com.example.vibereader.data.repository.BookRepository
import com.example.vibereader.data.repository.DeviceBookScanner
import com.example.vibereader.data.room.BookChapterEntity
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.BookReadingThemeEntity
import com.example.vibereader.data.room.HighlightEntity
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.data.room.ReadingProgressEntity
import com.example.vibereader.data.room.ReaderPreferenceEntity
import com.example.vibereader.model.AiProvider
import com.example.vibereader.model.ProviderKeyConfig
import com.example.vibereader.model.ReadingTheme
import com.example.vibereader.util.PdfPageCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
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
)

data class PdfSelectionPoint(val x: Float, val y: Float)

data class PdfSelectionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private data class PdfSpeechPage(
    val text: String,
    val charRects: List<PdfSelectionRect?>,
)

data class AiTaskResult(
    val title: String,
    val content: String,
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
    private val stateLock = Any()
    private val renderingPages = mutableSetOf<String>()
    private val renderedPdfPages = mutableSetOf<String>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var loadedBookId: String? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfRendererBookId: String? = null
    private var progressJob: Job? = null
    private var pdfSelectionJob: Job? = null
    private var pdfHighlightsJob: Job? = null
    private var pdfPageTextJob: Job? = null
    private val pdfSpeechPages = mutableMapOf<Int, PdfSpeechPage>()

    fun loadBook(bookId: String) {
        if (loadedBookId == bookId) return
        loadedBookId = bookId
        pdfSelectionJob?.cancel()
        pdfHighlightsJob?.cancel()
        pdfPageTextJob?.cancel()
        pdfSpeechPages.clear()
        viewModelScope.launch {
            _uiState.value = ReaderUiState(isLoading = true)
            closePdfRenderer()
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
                        ),
                    )
                    setPdfPageIndex(page)
                } else {
                    val savedLocation = EPUB_LOCATION_REGEX.matchEntire(location)
                    _uiState.value = ReaderUiState(
                        isLoading = false,
                        book = book,
                        chapters = chapters,
                        settings = ReaderSettings(
                            scrollMode = savedScrollMode,
                            readingTheme = savedReadingTheme,
                        ),
                        initialEpubChapter = savedLocation?.groupValues?.get(1)?.toIntOrNull() ?: 0,
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
        _uiState.value = state.copy(settings = settings)
        val bookId = state.book?.id ?: return
        if (state.settings.readingTheme == settings.readingTheme) return
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        val page = pdfSpeechPages[pageIndex]
        if (page == null || start !in page.text.indices || end <= start) {
            clearPdfSpeechHighlight()
            return
        }
        val rects = mergeSpeechRects(
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
        selectPdfTextRange(pageIndex, xFraction, yFraction, xFraction, yFraction)
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
        if (isStartHandle) {
            selectPdfTextRange(pageIndex, xFraction, yFraction, end.x, end.y)
        } else {
            selectPdfTextRange(pageIndex, start.x, start.y, xFraction, yFraction)
        }
    }

    fun selectPdfTextRange(
        pageIndex: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ) {
        val state = _uiState.value
        val book = state.book?.takeIf { it.format.equals("PDF", ignoreCase = true) } ?: return
        if (
            pageIndex !in 0 until state.pdfPageCount ||
            listOf(startX, startY, endX, endY).any { !it.isFinite() || it !in 0f..1f }
        ) return

        pdfSelectionJob?.cancel()
        pdfSelectionJob = viewModelScope.launch {
            try {
                val selection = withContext(Dispatchers.IO) {
                    val file = File(book.filePath)
                    if (!file.isFile) throw IOException("The local PDF file is missing.")
                    PDFBoxResourceLoader.init(appContext)
                    PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
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
                        stripper.getText(document)

                        val validPositions = positions.mapIndexedNotNull { index, position ->
                            val x = position.xDirAdj
                            val y = position.yDirAdj
                            val width = position.widthDirAdj
                            val height = position.heightDir
                            if (
                                x.isFinite() && y.isFinite() &&
                                width.isFinite() && height.isFinite() &&
                                width > 0f && height > 0f &&
                                position.unicode?.isNotEmpty() == true
                            ) index else null
                        }
                        fun nearestIndexAt(xFraction: Float, yFraction: Float): Int? =
                            validPositions.minByOrNull { index ->
                                val position = positions[index]
                                val centerX =
                                    (position.xDirAdj + position.widthDirAdj / 2f) / pageWidth
                                val centerY =
                                    (position.yDirAdj - position.heightDir / 2f) / pageHeight
                                val dx = centerX - xFraction
                                val dy = centerY - yFraction
                                dx * dx + dy * dy
                            }
                        val firstIndex = nearestIndexAt(startX, startY)
                            ?: return@withContext null
                        val lastIndex = nearestIndexAt(endX, endY)
                            ?: return@withContext null
                        val firstWord = wordRangeAt(positions, firstIndex)
                            ?: return@withContext null
                        val lastWord = wordRangeAt(positions, lastIndex)
                            ?: return@withContext null
                        val firstPositionIndex = minOf(firstWord.first, lastWord.first)
                        val lastPositionIndex = maxOf(firstWord.last, lastWord.last)
                        val selectedPositions = positions.subList(
                            firstPositionIndex,
                            lastPositionIndex + 1,
                        )
                        val selectedText = selectedPositions
                            .joinToString(separator = "") { it.unicode.orEmpty() }
                            .trim()
                        if (selectedText.isEmpty()) {
                            return@use null
                        }
                        val highlightRects = selectedPositions.mapNotNull { position ->
                            if (
                                !position.xDirAdj.isFinite() ||
                                !position.yDirAdj.isFinite() ||
                                !position.widthDirAdj.isFinite() ||
                                !position.heightDir.isFinite() ||
                                position.widthDirAdj <= 0f ||
                                position.heightDir <= 0f
                            ) {
                                return@mapNotNull null
                            }
                            val left = (position.xDirAdj / pageWidth).coerceIn(0f, 1f)
                            val top = ((position.yDirAdj - position.heightDir) / pageHeight)
                                .coerceIn(0f, 1f)
                            val right = ((position.xDirAdj + position.widthDirAdj) / pageWidth)
                                .coerceIn(0f, 1f)
                            val bottom = (position.yDirAdj / pageHeight).coerceIn(0f, 1f)
                            if (
                                left.isFinite() && top.isFinite() &&
                                right.isFinite() && bottom.isFinite() &&
                                right > left && bottom > top
                            ) {
                                PdfSelectionRect(left, top, right, bottom)
                            } else {
                                null
                            }
                        }
                        if (highlightRects.isEmpty()) return@use null
                        val firstPosition = positions[firstPositionIndex]
                        val lastPosition = positions[lastPositionIndex]
                        ReaderSelection(
                            chapterIndex = pageIndex,
                            chapterTitle = "Page ${pageIndex + 1}",
                            text = selectedText,
                            startOffset = firstPositionIndex,
                            endOffset = lastPositionIndex + 1,
                            pdfHighlightRects = highlightRects,
                            pdfStartHandle = PdfSelectionPoint(
                                x = (firstPosition.xDirAdj / pageWidth).coerceIn(0f, 1f),
                                y = ((firstPosition.yDirAdj - firstPosition.heightDir / 2f) /
                                    pageHeight).coerceIn(0f, 1f),
                            ),
                            pdfEndHandle = PdfSelectionPoint(
                                x = ((lastPosition.xDirAdj + lastPosition.widthDirAdj) /
                                    pageWidth).coerceIn(0f, 1f),
                                y = ((lastPosition.yDirAdj - lastPosition.heightDir / 2f) /
                                    pageHeight).coerceIn(0f, 1f),
                            ),
                        )
                    }
                }
                coroutineContext.ensureActive()
                if (selection == null) {
                    showMessage("No selectable text was found at this position.")
                } else {
                    if (_uiState.value.book?.id == book.id) setSelection(selection)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not select PDF text.")
            }
        }
    }

    private fun wordRangeAt(
        positions: List<TextPosition>,
        selectedIndex: Int,
    ): IntRange? {
        fun isWordPosition(index: Int): Boolean {
            val character = positions.getOrNull(index)?.unicode?.firstOrNull() ?: return false
            return character.isLetterOrDigit() || character == '\'' || character == '-'
        }

        if (!isWordPosition(selectedIndex)) return null
        var first = selectedIndex
        var last = selectedIndex
        while (isWordPosition(first - 1)) first--
        while (isWordPosition(last + 1)) last++
        return first..last
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
            pdfHighlightsJob = viewModelScope.launch {
                database.highlightDao().getHighlightsForPage(book.id, page).collect { highlights ->
                    val current = _uiState.value
                    if (current.book?.id == book.id && current.pdfPageIndex == page) {
                        _uiState.value = current.copy(savedPdfHighlights = highlights)
                    }
                }
            }
            pdfPageTextJob?.cancel()
            pdfPageTextJob = viewModelScope.launch {
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
        val cacheBookKey =
            "${book.id}:${bookFile.absolutePath}:${bookFile.length()}:${bookFile.lastModified()}:$targetHeight"
        val renderKey = "$cacheBookKey:$page:$targetWidth"
        synchronized(stateLock) {
            if (renderKey in renderedPdfPages) return
            if (!renderingPages.add(renderKey)) return
        }

        viewModelScope.launch {
            var shouldPersistToDisk = false
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    PdfPageCache.get(appContext, cacheBookKey, page, targetWidth)?.let { cached ->
                        return@withContext cached
                    }
                    val renderedBitmap = synchronized(stateLock) {
                        if (pdfRendererBookId != book.id) {
                            throw IOException("PDF renderer is not open for this book.")
                        }
                        val renderer = pdfRenderer ?: throw IOException("PDF renderer is closed.")
                        val pdfPage = renderer.openPage(page)
                        try {
                            renderPageCrisp(
                                page = pdfPage,
                                targetWidth = targetWidth,
                                targetHeight = targetHeight,
                                zoomScale = 1f,
                                maxBitmapPixels = maxPdfBitmapPixels(),
                                maxBitmapDimension = MAX_PDF_BITMAP_DIMENSION,
                            ) ?: throw IOException("Not enough memory to render this PDF page.")
                        } finally {
                            pdfPage.close()
                        }
                    }
                    shouldPersistToDisk = true
                    PdfPageCache.putMemory(cacheBookKey, page, renderedBitmap)
                    renderedBitmap
                }
                synchronized(stateLock) {
                    renderedPdfPages.add(renderKey)
                }
                val current = _uiState.value
                if (current.book?.id == book.id) {
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
                    viewModelScope.launch(Dispatchers.IO) {
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
        persistProgress("epub:$chapter:${scrollOffsetPx.coerceAtLeast(0)}", progress.coerceIn(0f, 1f))
    }

    fun saveSelectedQuote() {
        val selection = _uiState.value.selection ?: return
        viewModelScope.launch {
            setWorking(true)
            try {
                database.quoteDao().upsert(
                    QuoteEntity(
                        bookId = _uiState.value.book?.id ?: return@launch,
                        text = selection.text,
                        chapter = selection.chapterTitle,
                    ),
                )
                clearSelection()
                showMessage("Quote saved.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not save this quote.")
            } finally {
                setWorking(false)
            }
        }
    }

    fun saveHighlight() {
        val selection = _uiState.value.selection ?: return
        viewModelScope.launch {
            setWorking(true)
            try {
                val bookId = _uiState.value.book?.id ?: return@launch
                database.highlightDao().upsert(
                    HighlightEntity(
                        bookId = bookId,
                        text = selection.text,
                        startOffset = selection.startOffset,
                        endOffset = selection.endOffset,
                        note = selection.chapterTitle,
                        pageIndex = selection.chapterIndex.takeIf {
                            _uiState.value.book?.format.equals("PDF", ignoreCase = true)
                        },
                        startCharOffset = selection.startOffset,
                        endCharOffset = selection.endOffset,
                        colorHex = "#FFFF00",
                        boundsJson = selection.pdfHighlightRects
                            .takeIf { it.isNotEmpty() }
                            ?.let(::encodePdfHighlightBounds),
                    ),
                )
                clearSelection()
                showMessage("Highlight saved.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "Could not save this highlight.")
            } finally {
                setWorking(false)
            }
        }
    }

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

    fun translateSelection(targetLanguage: String) {
        val selection = _uiState.value.selection ?: return
        val book = _uiState.value.book ?: return
        runAiTask(
            AiTask.TRANSLATE,
            selection.text,
            book,
            selection.chapterTitle,
            targetLanguage = targetLanguage,
        )
    }

    fun summarizeChapter(chapterIndex: Int) {
        val state = _uiState.value
        val chapter = state.chapters.getOrNull(chapterIndex) ?: return
        val book = state.book ?: return
        runAiTask(AiTask.SUMMARIZE, chapter.text, book, chapter.title)
    }

    fun translateChapter(chapterIndex: Int, targetLanguage: String) {
        val state = _uiState.value
        val chapter = state.chapters.getOrNull(chapterIndex) ?: return
        val book = state.book ?: return
        runAiTask(
            AiTask.TRANSLATE,
            chapter.text,
            book,
            chapter.title,
            targetLanguage = targetLanguage,
        )
    }

    fun summarizePdfPage(pageIndex: Int) {
        val state = _uiState.value
        val book = state.book?.takeIf { it.format.equals("PDF", ignoreCase = true) } ?: return
        if (pageIndex !in 0 until state.pdfPageCount) return
        viewModelScope.launch {
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

    fun translatePdfPage(pageIndex: Int, targetLanguage: String) {
        val state = _uiState.value
        val book = state.book?.takeIf { it.format.equals("PDF", ignoreCase = true) } ?: return
        if (pageIndex !in 0 until state.pdfPageCount) return
        viewModelScope.launch {
            try {
                val pageText = withContext(Dispatchers.IO) {
                    extractPdfPageText(book, pageIndex)
                }
                if (pageText.isBlank()) {
                    showMessage("No text could be extracted from this PDF page.")
                } else {
                    runAiTask(
                        AiTask.TRANSLATE,
                        pageText,
                        book,
                        "Page ${pageIndex + 1}",
                        targetLanguage = targetLanguage,
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
        viewModelScope.launch {
            try {
                val speechPage = withContext(Dispatchers.IO) {
                    extractPdfSpeechPage(book, pageIndex)
                }
                if (speechPage.text.isBlank()) {
                    showMessage("No readable text was found on this PDF page.")
                } else {
                    pdfSpeechPages[pageIndex] = speechPage
                    while (pdfSpeechPages.size > MAX_CACHED_PDF_PAGES) {
                        pdfSpeechPages.remove(pdfSpeechPages.keys.first())
                    }
                    onTextReady(speechPage.text)
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

    private fun extractPdfPageText(book: BookEntity, pageIndex: Int): String {
        val file = File(book.filePath)
        if (!file.isFile) throw IOException("The local PDF file is missing.")
        PDFBoxResourceLoader.init(appContext)
        return PDDocument.load(file).use { document ->
            if (pageIndex !in 0 until document.numberOfPages) {
                throw IOException("The requested PDF page is unavailable.")
            }
            PDFTextStripper().apply {
                startPage = pageIndex + 1
                endPage = pageIndex + 1
            }.getText(document).trim()
        }
    }

    private fun extractPdfSpeechPage(book: BookEntity, pageIndex: Int): PdfSpeechPage {
        val file = File(book.filePath)
        if (!file.isFile) throw IOException("The local PDF file is missing.")
        PDFBoxResourceLoader.init(appContext)
        return PDDocument.load(file).use { document ->
            if (pageIndex !in 0 until document.numberOfPages) {
                throw IOException("The requested PDF page is unavailable.")
            }
            val page = document.getPage(pageIndex)
            val pageWidth = page.mediaBox.width.coerceAtLeast(1f)
            val pageHeight = page.mediaBox.height.coerceAtLeast(1f)
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
            val characterRects = MutableList<PdfSelectionRect?>(text.length) { null }
            var searchFrom = 0
            positions.forEach { position ->
                val unicode = position.unicode.orEmpty()
                if (unicode.isEmpty()) return@forEach
                val match = text.indexOf(unicode, searchFrom)
                if (match < 0) return@forEach
                val rect = PdfSelectionRect(
                    left = (position.xDirAdj / pageWidth).coerceIn(0f, 1f),
                    top = ((position.yDirAdj - position.heightDir) / pageHeight).coerceIn(0f, 1f),
                    right = ((position.xDirAdj + position.widthDirAdj) / pageWidth)
                        .coerceIn(0f, 1f),
                    bottom = (position.yDirAdj / pageHeight).coerceIn(0f, 1f),
                )
                repeat(unicode.length) { offset ->
                    val charIndex = match + offset
                    if (charIndex in characterRects.indices) characterRects[charIndex] = rect
                }
                searchFrom = match + unicode.length
            }
            PdfSpeechPage(text, characterRects)
        }
    }

    private fun mergeSpeechRects(rects: List<PdfSelectionRect>): List<PdfSelectionRect> {
        val merged = mutableListOf<PdfSelectionRect>()
        rects.groupBy { (it.top * 100).roundToInt() }
            .toSortedMap()
            .values
            .forEach { lineRects ->
                val sorted = lineRects.sortedBy(PdfSelectionRect::left)
                var current: PdfSelectionRect? = null
                sorted.forEach { rect ->
                    val previous = current
                    if (previous != null && rect.left <= previous.right + 0.015f) {
                        current = previous.copy(
                            right = maxOf(previous.right, rect.right),
                            top = minOf(previous.top, rect.top),
                            bottom = maxOf(previous.bottom, rect.bottom),
                        )
                    } else {
                        current?.let(merged::add)
                        current = rect
                    }
                }
                current?.let(merged::add)
            }
        return merged
    }

    private fun runAiTask(
        task: AiTask,
        text: String,
        book: BookEntity,
        chapterTitle: String,
        selection: ReaderSelection? = null,
        targetLanguage: String = "",
        processingPageIndex: Int? = null,
    ) {
        if (task == AiTask.TRANSLATE && targetLanguage.isBlank()) {
            showMessage("Enter a target language before translating.")
            return
        }
        if (text.isBlank()) {
            showMessage("There is no readable text to send to the AI assistant.")
            return
        }
        viewModelScope.launch {
            if (processingPageIndex != null) {
                _uiState.value = _uiState.value.copy(aiProcessingPageIndex = processingPageIndex)
            }
            setWorking(true)
            try {
                val config = withContext(Dispatchers.IO) {
                    val store = ApiKeyStore(appContext)
                    val provider = store.getSelectedAiProvider()
                    val providerConfigs = store.getEnabledProviderConfigs(provider)
                    val primary = providerConfigs.first()
                    val apiKey = primary.apiKey.takeIf(String::isNotBlank)
                        ?: throw IOException("Add an API key for the selected AI provider in Settings.")
                    Triple(
                        store,
                        primary.copy(apiKey = apiKey),
                        providerConfigs.drop(1),
                    )
                }
                val response = withContext(Dispatchers.IO) {
                    requestAiTaskForLongText(
                        store = config.first,
                        primary = config.second,
                        fallbacks = config.third,
                        task = task,
                        text = text,
                        targetLanguage = targetLanguage,
                    )
                }
                if (_uiState.value.book?.id != book.id) return@launch
                if (task == AiTask.EXTRACT_QUOTE && selection != null) {
                    val (header, quote) = parseQuote(response)
                    database.quoteDao().upsert(
                        QuoteEntity(
                            bookId = book.id,
                            text = "$header\n\n$quote",
                            chapter = chapterTitle,
                        ),
                    )
                    clearSelection()
                    showMessage("AI quote saved.")
                } else {
                    _uiState.value = _uiState.value.copy(
                        aiResult = AiTaskResult(
                            if (task == AiTask.TRANSLATE) {
                                "Translation to ${targetLanguage.trim()} · $chapterTitle"
                            } else {
                                "${task.title} · $chapterTitle"
                            },
                            response,
                        ),
                    )
                }

            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.localizedMessage ?: "AI ${task.title.lowercase()} failed.")
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
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
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

    private fun requestAiTaskForLongText(
        store: ApiKeyStore,
        primary: ProviderKeyConfig,
        fallbacks: List<ProviderKeyConfig>,
        task: AiTask,
        text: String,
        targetLanguage: String,
    ): String {
        if (task == AiTask.EXTRACT_QUOTE || text.length <= MAX_AI_CHUNK_CHARS) {
            return requestAiTaskWithFallback(
                store,
                primary,
                fallbacks,
                task,
                text,
                targetLanguage,
            )
        }
        val chunks = splitAiText(text, MAX_AI_CHUNK_CHARS)
        val partialResults = chunks.mapIndexed { index, chunk ->
            requestAiTaskWithFallback(
                store,
                primary,
                fallbacks,
                task,
                chunk,
                targetLanguage,
            ).let { result ->
                if (task == AiTask.SUMMARIZE) "Part ${index + 1}: $result" else result
            }
        }
        return if (task == AiTask.SUMMARIZE && partialResults.size > 1) {
            requestAiTaskWithFallback(
                store,
                primary,
                fallbacks,
                AiTask.SUMMARIZE,
                partialResults.joinToString("\n\n"),
                targetLanguage,
            )
        } else {
            partialResults.joinToString(separator = "\n\n")
        }
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

    private fun requestAiTaskWithFallback(
        store: ApiKeyStore,
        primary: ProviderKeyConfig,
        fallbacks: List<ProviderKeyConfig>,
        task: AiTask,
        selectedText: String,
        targetLanguage: String,
    ): String {
        val configs = listOf(primary) + fallbacks
        var lastRateLimit: AiProviderHttpException? = null
        configs.forEachIndexed { index, config ->
            val model = store.getAiModel(config.provider)
                ?.takeIf(String::isNotBlank)
                ?: config.provider.defaultModel
            try {
                return requestAiTask(
                    config.provider,
                    config.apiKey,
                    model,
                    task,
                    selectedText,
                    targetLanguage,
                )
            } catch (error: AiProviderHttpException) {
                if (error.statusCode != HTTP_TOO_MANY_REQUESTS) throw error
                lastRateLimit = error
                if (index == configs.lastIndex) throw error
            }
        }
        throw lastRateLimit ?: IOException("No enabled AI provider is available.")
    }

    private fun requestAiTask(
        provider: AiProvider,
        apiKey: String,
        model: String,
        task: AiTask,
        selectedText: String,
        targetLanguage: String,
    ): String {
        val normalizedTargetLanguage = when (targetLanguage.trim().lowercase()) {
            "bn", "bangla", "bengali" -> "Bangla (Bengali)"
            else -> targetLanguage.trim()
        }
        val systemPrompt = when (task) {
            AiTask.SUMMARIZE ->
                "Summarize only the supplied book excerpt faithfully. Do not invent details."
            AiTask.EXPLAIN ->
                "Explain the supplied book excerpt clearly and faithfully. Do not invent details."
            AiTask.EXTRACT_QUOTE ->
                "Extract one concise, faithful quote from the supplied text. Return only valid JSON " +
                    "with exactly two string fields: \"header\" (one uppercase word) and \"quote\" " +
                    "(the meaningful excerpt). Do not add facts or commentary."
            AiTask.TRANSLATE ->
                "You are an expert literary translator. Translate the provided book content directly " +
                    "into $normalizedTargetLanguage. Maintain the original tone and formatting. " +
                    "Provide only the translated text without extra conversational commentary."
        }
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put(
                "messages",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "role",
                                "system",
                            )
                            .put("content", systemPrompt),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                if (task == AiTask.TRANSLATE) {
                                    "Target Language: $normalizedTargetLanguage\n\n" +
                                        "Content to Translate:\n$selectedText"
                                } else {
                                    selectedText
                                },
                            ),
                    ),
            )
        val request = Request.Builder()
            .url("${provider.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .apply {
                if (provider == AiProvider.OPENROUTER) {
                    header("HTTP-Referer", APP_REFERER)
                    header("X-Title", APP_TITLE)
                }
            }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AiProviderHttpException(
                    statusCode = response.code,
                    message = "${provider.displayName} returned HTTP ${response.code}: " +
                        body.take(MAX_AI_ERROR_BODY_CHARS),
                )
            }
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .takeIf(String::isNotBlank)
                ?: throw IOException("AI provider returned an empty response.")
        }
    }

    private fun parseQuote(response: String): Pair<String, String> {
        val json = response
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val result = try {
            JSONObject(json)
        } catch (error: org.json.JSONException) {
            throw IOException("AI returned an invalid quote format.", error)
        }
        val header = result.optString("header").trim()
        val quote = result.optString("quote").trim()
        if (!header.matches(Regex("[A-Z][A-Z -]{0,23}")) || quote.isBlank()) {
            throw IOException("AI returned an invalid quote format.")
        }
        return header to quote
    }

    private fun closePdfRenderer() {
        synchronized(stateLock) {
            renderingPages.clear()
            renderedPdfPages.clear()
            pdfRenderer?.close()
            pdfRenderer = null
            pdfRendererBookId = null
            pdfDescriptor?.close()
            pdfDescriptor = null
        }
    }

    override fun onCleared() {
        closePdfRenderer()
        httpClient.dispatcher.cancelAll()
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
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val MAX_AI_ERROR_BODY_CHARS = 300
        const val MAX_AI_CHUNK_CHARS = 12_000
        const val APP_REFERER = "https://vibereader.app"
        const val APP_TITLE = "VibeReader"
        const val MAX_PDF_RENDER_WIDTH = 3072
        const val MAX_PDF_BITMAP_PIXELS = 12_000_000L
        const val MAX_PDF_BITMAP_DIMENSION = 8192
        const val MAX_CACHED_PDF_PAGES = 3
        const val PROGRESS_SAVE_DELAY_MS = 500L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EPUB_LOCATION_REGEX = Regex("""epub:(\d+):(\d+)""")
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
        TRANSLATE("Translation"),
    }

    private class AiProviderHttpException(
        val statusCode: Int,
        message: String,
    ) : IOException(message)

}

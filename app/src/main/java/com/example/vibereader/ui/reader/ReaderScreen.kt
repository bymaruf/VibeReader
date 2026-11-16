package com.example.vibereader.ui.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.document.PdfDocumentEngine
import com.example.vibereader.data.local.DictionaryPreferences
import com.example.vibereader.data.room.BookChapterEntity
import com.example.vibereader.model.ReaderTtsVoice
import com.example.vibereader.model.ReadingTheme
import com.example.vibereader.util.DictionaryRouter
import com.example.vibereader.util.FreeAiTtsPlayer
import com.example.vibereader.util.SpeechProgress
import com.example.vibereader.util.TtsManager
import com.example.vibereader.util.displayTitle
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class SpeechTarget(
    val chapterIndex: Int,
    val chapterTitle: String,
    val sourceOffset: Int,
    val isPdf: Boolean,
    val pageIndex: Int,
    val highlightPdfPageText: Boolean = false,
)

private data class ResolvedReadingTheme(
    val background: Color,
    val text: Color,
    val isDarkCanvas: Boolean,
)

private fun ReadingTheme.resolve(isSystemDark: Boolean): ResolvedReadingTheme = when (this) {
    ReadingTheme.SYSTEM -> if (isSystemDark) {
        ResolvedReadingTheme(Color(0xFF121212), Color(0xFFECECEC), true)
    } else {
        ResolvedReadingTheme(Color(0xFFFFFFFF), Color(0xFF1A1A1A), false)
    }
    ReadingTheme.OLED_BLACK -> ResolvedReadingTheme(Color(0xFF000000), Color(0xFFE6E6E6), true)
    ReadingTheme.SEPIA -> ResolvedReadingTheme(Color(0xFFF4ECD8), Color(0xFF5B4636), false)
    ReadingTheme.LIGHT -> ResolvedReadingTheme(Color(0xFFFFFFFF), Color(0xFF1A1A1A), false)
}

/**
 * Read-only map passed into the PDF pager/column. Every lookup synchronously
 * checks the engine's [com.example.vibereader.data.document.PdfDocumentEngine]
 * LRU cache during composition, then falls back to the ViewModel's persisted
 * page bitmaps. When both exist, the higher-resolution bitmap wins so zoomed
 * pages keep the ViewModel's high-quality render.
 */
private class PdfDisplayBitmapMap(
    private val persistedBitmaps: Map<Int, Bitmap>,
    private val engine: PdfDocumentEngine,
) : Map<Int, Bitmap> by persistedBitmaps {
    override fun get(key: Int): Bitmap? {
        val cached = engine.getCachedPage(key)
        val persisted = persistedBitmaps[key]
        return when {
            cached == null -> persisted
            persisted == null -> cached
            persisted.width >= cached.width -> persisted
            else -> cached
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val database = remember(context) { VibeReaderDatabase.getInstance(context.applicationContext) }
    val viewModel: ReaderViewModel = viewModel(
        factory = remember(database) {
            ReaderViewModel.Factory(context.applicationContext, database)
        },
    )
    val state by viewModel.uiState.collectAsState()
    val pdfEngine = remember(bookId) { PdfDocumentEngine(context.applicationContext) }
    val pdfCacheVersion by pdfEngine.cacheVersion.collectAsState()
    DisposableEffect(pdfEngine) {
        onDispose { pdfEngine.clearPageCache() }
    }
    val ttsManager = remember { TtsManager(context) }
    val edgeTtsPlayer = remember { FreeAiTtsPlayer(context) }
    val audioPrefs = remember {
        context.getSharedPreferences(TTS_PREFERENCES, Context.MODE_PRIVATE)
    }
    val dictionaryPreferences = remember { DictionaryPreferences(context) }
    var ttsVoice by remember {
        mutableStateOf<ReaderTtsVoice>(
            ReaderTtsVoice.fromStoredName(audioPrefs.getString(TTS_VOICE_KEY, null)),
        )
    }
    val androidSpeechProgress by ttsManager.speechProgress.collectAsState()
    val edgeSpeechProgress by edgeTtsPlayer.speechProgress.collectAsState()
    val activeSpeechProgress: SpeechProgress = if (
        ttsVoice == ReaderTtsVoice.ANDROID_SYSTEM || edgeTtsPlayer.isUsingNativeFallback.value
    ) {
        androidSpeechProgress
    } else {
        edgeSpeechProgress
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var assistantVisible by rememberSaveable { mutableStateOf(false) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var pdfJumpRequest by remember(bookId) { mutableIntStateOf(-1) }
    var currentEpubChapter by remember(bookId) { mutableIntStateOf(0) }
    var currentEpubPageIndex by remember(bookId) { mutableIntStateOf(0) }
    var speechTarget by remember(bookId) { mutableStateOf<SpeechTarget?>(null) }
    var epubSelectionVersion by remember(bookId) { mutableIntStateOf(0) }

    val readingTheme = state.settings.readingTheme
    val isSystemDark = isSystemInDarkTheme()
    val resolvedTheme = remember(readingTheme, isSystemDark) { readingTheme.resolve(isSystemDark) }
    val readerBackground = resolvedTheme.background
    val readerForeground = resolvedTheme.text

    val chapterListState = rememberLazyListState()

    LaunchedEffect(state.initialEpubChapter, state.initialEpubScrollOffset, state.book?.id) {
        if (state.book != null && !state.book!!.format.equals("PDF", ignoreCase = true)) {
            currentEpubChapter = state.initialEpubChapter.coerceAtLeast(0)
            currentEpubPageIndex = state.initialEpubScrollOffset.coerceAtLeast(0)
        }
    }

    val startSpeech: (String, SpeechTarget) -> Unit = { text, target ->
        speechTarget = target
        edgeTtsPlayer.stop()
        ttsManager.stop()
        if (ttsVoice == ReaderTtsVoice.ANDROID_SYSTEM) {
            ttsManager.speak(text)
        } else {
            edgeTtsPlayer.speak(text, ttsVoice, ttsManager::speak)
        }
    }
    val speakAssistantText: (String) -> Unit = { text ->
        val selected = state.selection?.takeIf { it.text == text }
        val isPdf = state.book?.format.equals("PDF", ignoreCase = true)
        val trimmedPrefixLength = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        if (isPdf && selected != null) {
            viewModel.readPdfPageText(selected.chapterIndex) { pageText ->
                val selectionOffset = pageText.indexOf(text.trim(), ignoreCase = true)
                    .takeIf { it >= 0 }
                    ?: selected.startOffset
                startSpeech(
                    text.trim(),
                    SpeechTarget(
                        chapterIndex = selected.chapterIndex,
                        chapterTitle = selected.chapterTitle.ifBlank { "Page ${selected.chapterIndex + 1}" },
                        sourceOffset = selectionOffset + trimmedPrefixLength,
                        isPdf = true,
                        pageIndex = selected.chapterIndex,
                        highlightPdfPageText = true,
                    ),
                )
            }
        } else if (!isPdf && selected != null) {
            startSpeech(
                text.trim(),
                SpeechTarget(
                    chapterIndex = selected.chapterIndex,
                    chapterTitle = selected.chapterTitle,
                    sourceOffset = selected.startOffset + trimmedPrefixLength,
                    isPdf = false,
                    pageIndex = selected.chapterIndex,
                ),
            )
        } else {
            speechTarget = null
            if (ttsVoice == ReaderTtsVoice.ANDROID_SYSTEM) {
                ttsManager.speak(text)
            } else {
                edgeTtsPlayer.speak(text, ttsVoice, ttsManager::speak)
            }
        }
    }

    LaunchedEffect(bookId) {
        // Let the reader route compose and draw its lightweight loading
        // surface before any database or file work is scheduled. The
        // ViewModel keeps a short deferral so the open animation finishes
        // before parsing starts.
        withFrameNanos { }
        viewModel.loadBook(bookId)
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearMessage()
        }
    }

    DisposableEffect(edgeTtsPlayer, ttsManager) {
        onDispose {
            // Releasing MediaPlayer and the platform TTS engine can take
            // longer than a frame; do it off the main thread so pressing
            // Back returns to the library immediately.
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    edgeTtsPlayer.stop()
                    ttsManager.shutdown()
                }
            }
        }
    }

    LaunchedEffect(speechTarget, activeSpeechProgress) {
        val target = speechTarget ?: return@LaunchedEffect
        if (target.highlightPdfPageText && activeSpeechProgress.isActive) {
            viewModel.updatePdfSpeechHighlight(
                pageIndex = target.pageIndex,
                start = target.sourceOffset + activeSpeechProgress.startCharIndex,
                end = target.sourceOffset + activeSpeechProgress.endCharIndex,
            )
        } else if (!activeSpeechProgress.isActive) {
            viewModel.clearPdfSpeechHighlight()
        }
    }

    // Immersive mode: hide status/navigation bars when chrome is hidden.
    DisposableEffect(chromeVisible) {
        val activity = context.findActivity()
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, chromeVisible)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = window.insetsController
                if (controller != null) {
                    if (chromeVisible) {
                        controller.show(WindowInsets.Type.systemBars())
                    } else {
                        controller.hide(WindowInsets.Type.systemBars())
                        controller.systemBarsBehavior =
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (chromeVisible) {
                    View.SYSTEM_UI_FLAG_VISIBLE
                } else {
                    (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
                }
            }
        }
        onDispose {
            if (window != null) {
                // Restore the main-tab chrome immediately when the reader
                // leaves. Setting decor-fitting to false first collapses the
                // platform nav inset, so CompactNavigationBar's single
                // navigationBarsPadding() cannot add a second ~48dp strip.
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val view = window.decorView
                val controller = WindowCompat.getInsetsController(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                controller.isAppearanceLightNavigationBars = false
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                ViewCompat.requestApplyInsets(view)
            }
        }
    }

    val title = state.book?.displayTitle().orEmpty().ifBlank { "Reader" }
    val isPdf = state.book?.format.equals("PDF", ignoreCase = true)
    val isHorizontalEpub = !isPdf && state.settings.scrollMode == ScrollMode.HORIZONTAL
    var epubTotalPages by remember(bookId, isHorizontalEpub) { mutableIntStateOf(0) }
    var epubVerticalProgress by remember(bookId, isHorizontalEpub) { mutableStateOf(0f) }
    val currentPageLabel = when {
        isPdf -> "${state.pdfPageIndex + 1} / ${state.pdfPageCount.coerceAtLeast(1)}"
        isHorizontalEpub && epubTotalPages > 0 ->
            "${currentEpubPageIndex + 1} / $epubTotalPages"
        !isPdf && !isHorizontalEpub -> "${(epubVerticalProgress * 100).toInt().coerceIn(0, 100)}%"
        else -> {
            val idx = currentEpubChapter.coerceIn(0, (state.chapters.size - 1).coerceAtLeast(0))
            val ch = state.chapters.getOrNull(idx)
            ch?.title?.ifBlank { "Chapter ${idx + 1}" } ?: "Chapter"
        }
    }

    val pdfFile = remember(state.book?.filePath) {
        state.book?.filePath?.takeIf(String::isNotBlank)?.let(::File)
    }

    // Composition-safe merge of ViewModel-rendered bitmaps and the engine's
    // synchronous LRU cache. getCachedPage() is read during composition, so a
    // prefetched neighbour paints on the first frame instead of flashing a
    // spinner. pdfCacheVersion invalidates this map when a silent prefetch lands.
    val pdfPageBitmaps = remember(state.pdfPageBitmaps, pdfCacheVersion, pdfEngine) {
        PdfDisplayBitmapMap(state.pdfPageBitmaps, pdfEngine)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(readerBackground),
        containerColor = readerBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            ) {
                ReaderTopBar(
                    title = title,
                    foreground = readerForeground,
                    background = readerBackground.copy(alpha = 0.97f),
                    onBack = onBack,
                    onSettings = { settingsVisible = true },
                    onAssistant = { assistantVisible = true },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = chromeVisible && !state.isLoading && state.error == null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                ReaderBottomBar(
                    currentPageLabel = currentPageLabel,
                    isPdf = isPdf,
                    pdfCurrentPage = state.pdfPageIndex,
                    pdfTotalPages = state.pdfPageCount,
                    isHorizontalEpub = isHorizontalEpub,
                    epubCurrentPage = currentEpubPageIndex,
                    epubTotalPages = epubTotalPages,
                    verticalProgress = epubVerticalProgress,
                    showVerticalProgress = !isPdf && !isHorizontalEpub && state.chapters.isNotEmpty(),
                    chapterCount = state.chapters.size,
                    currentChapter = currentEpubChapter,
                    foreground = readerForeground,
                    background = readerBackground.copy(alpha = 0.97f),
                    onPdfPageChange = { page ->
                        pdfJumpRequest = page
                        viewModel.setPdfPageIndex(page)
                    },
                    onEpubPageChange = { page -> currentEpubPageIndex = page },
                    onVerticalProgressChange = { epubVerticalProgress = it },
                    onChapterChange = { chapter ->
                        currentEpubChapter = chapter
                        coroutineScope.launch {
                            if (chapter in state.chapters.indices) {
                                chapterListState.scrollToItem(chapter)
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(readerBackground),
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = readerForeground,
                )

                state.error != null -> Text(
                    text = state.error.orEmpty(),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    color = readerForeground,
                    style = MaterialTheme.typography.bodyLarge,
                )

                isPdf -> {
                    PdfReaderCanvas(
                        bookId = bookId,
                        pageCount = state.pdfPageCount,
                        currentPage = state.pdfPageIndex,
                        scrollMode = state.settings.scrollMode,
                        pageBitmaps = pdfPageBitmaps,
                        savedHighlights = state.savedPdfHighlights,
                        aiProcessingPageIndex = state.aiProcessingPageIndex,
                        title = title,
                        theme = state.settings.readingTheme,
                        selection = state.selection,
                        speechSelection = state.speechHighlight,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(readerBackground),
                        jumpToPageRequest = pdfJumpRequest.takeIf { it >= 0 },
                        onPageChanged = viewModel::setPdfPageIndex,
                        onRenderPage = { pageIndex, width, height, zoomScale ->
                            viewModel.requestPdfPageRender(
                                pageIndex = pageIndex,
                                requestedWidthPx = width,
                                requestedHeightPx = height,
                                zoomScale = zoomScale,
                            )
                            // Warm the engine's next/previous window while the
                            // ViewModel renders the current page. At zoom > 1
                            // the ViewModel's high-resolution render is the
                            // authority; base-resolution neighbours are only
                            // prefetched at fit scale.
                            if (zoomScale <= 1.01f) {
                                pdfFile?.let { file ->
                                    pdfEngine.prefetchAround(
                                        file = file,
                                        pageIndex = pageIndex,
                                        targetWidth = width,
                                        targetHeight = height,
                                    )
                                }
                            }
                        },
                        onCanvasTap = { chromeVisible = !chromeVisible },
                        onTextLongPress = viewModel::selectPdfTextAt,
                        onSelectionHandleDrag = viewModel::movePdfSelectionHandle,
                        onHighlightTap = viewModel::selectPdfHighlight,
                    )
                }

                else -> {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val epubPages = if (isHorizontalEpub) {
                            rememberEpubPages(
                                chapters = state.chapters,
                                viewportWidthDp = maxWidth,
                                viewportHeightDp = maxHeight,
                                fontSize = state.settings.fontSizeSp.sp,
                                lineHeight = (state.settings.fontSizeSp * state.settings.lineHeightMultiplier).sp,
                                marginDp = state.settings.marginDp,
                            )
                        } else {
                            emptyList()
                        }
                        LaunchedEffect(epubPages.size) {
                            epubTotalPages = epubPages.size
                            if (epubPages.isNotEmpty()) {
                                val restored = state.initialEpubScrollOffset.coerceIn(0, epubPages.lastIndex)
                                // Prefer restoring by saved page index; fall back to saved chapter.
                                val target = if (state.initialEpubScrollOffset >= 0) restored else {
                                    epubPages.indexOfFirst { it.chapterIndex == state.initialEpubChapter }
                                        .takeIf { it >= 0 } ?: 0
                                }
                                if (currentEpubPageIndex > epubPages.lastIndex) currentEpubPageIndex = epubPages.lastIndex
                                if (currentEpubPageIndex == 0 && target > 0) currentEpubPageIndex = target
                            }
                        }
                        EpubReaderCanvas(
                            chapters = state.chapters,
                            epubPages = epubPages,
                            listState = chapterListState,
                            settings = state.settings,
                            currentChapterIndex = currentEpubChapter,
                            currentEpubPageIndex = currentEpubPageIndex,
                            foreground = readerForeground,
                            background = readerBackground,
                            speechHighlightStart = if (!state.speechHighlight?.text.isNullOrEmpty() &&
                                speechTarget?.chapterIndex == currentEpubChapter
                            ) {
                                speechTarget?.sourceOffset?.plus(activeSpeechProgress.startCharIndex)
                            } else null,
                            speechHighlightEnd = if (!state.speechHighlight?.text.isNullOrEmpty() &&
                                speechTarget?.chapterIndex == currentEpubChapter
                            ) {
                                speechTarget?.sourceOffset?.plus(activeSpeechProgress.endCharIndex)
                            } else null,
                            selection = state.selection,
                            onCanvasTap = { chromeVisible = !chromeVisible },
                            onChapterChanged = { index ->
                                currentEpubChapter = index
                                viewModel.updateEpubProgress(
                                    chapterIndex = index,
                                    scrollOffsetPx = 0,
                                    progress = if (state.chapters.isEmpty()) 0f
                                    else (index + 1).toFloat() / state.chapters.size.toFloat(),
                                )
                            },
                            onVerticalScrollProgressChanged = { progress ->
                                epubVerticalProgress = progress
                                viewModel.updateEpubProgress(
                                    chapterIndex = currentEpubChapter,
                                    scrollOffsetPx = 0,
                                    progress = progress,
                                )
                            },
                            onEpubPageChanged = { pageIndex, chapterIndex, totalPages ->
                                currentEpubPageIndex = pageIndex
                                currentEpubChapter = chapterIndex
                                val progress = if (totalPages > 0) {
                                    (pageIndex + 1).toFloat() / totalPages.toFloat()
                                } else 0f
                                viewModel.updateEpubProgress(
                                    chapterIndex = chapterIndex,
                                    scrollOffsetPx = pageIndex,
                                    progress = progress,
                                )
                            },
                            onSelectionChanged = viewModel::setSelection,
                            selectionVersion = epubSelectionVersion,
                        )
                    }
                }
            }

            if (state.selection != null && !state.isLoading) {
                val selectedText = state.selection?.text.orEmpty()
                val isSingleWord = DictionaryRouter.isSingleWord(selectedText)
                TextSelectionPopup(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    isWorking = state.isWorking,
                    isHighlighted = state.selection?.highlightId != null,
                    isSingleWord = isSingleWord,
                    foreground = readerForeground,
                    background = readerBackground.copy(alpha = 0.98f),
                    onSaveQuote = viewModel::saveSelectedQuote,
                    onCopy = {
                        copyReaderText(context, selectedText)
                        viewModel.clearSelection()
                    },
                    onTranslate = { launchNativeTranslator(context, selectedText) },
                    onDictionary = {
                        DictionaryRouter.launchDictionary(
                            context = context,
                            word = selectedText,
                            customPackageName = dictionaryPreferences.getCustomDictionaryPackage(),
                        )
                    },
                    onUnhighlight = viewModel::unhighlightSelection,
                    onHighlight = viewModel::saveHighlight,
                    onAskAi = { assistantVisible = true },
                )
            }
        }
    }

    if (settingsVisible) {
        ReaderSettingsBottomSheet(
            settings = state.settings,
            isPdf = isPdf,
            ttsVoice = ttsVoice,
            onTtsVoiceChange = { voice ->
                ttsVoice = voice
                audioPrefs.edit().putString(TTS_VOICE_KEY, voice.name).apply()
                edgeTtsPlayer.stop()
                ttsManager.stop()
            },
            onSettingsChange = viewModel::updateSettings,
            onScrollModeChange = viewModel::setScrollMode,
            onDismiss = { settingsVisible = false },
        )
    }

    if (assistantVisible) {
        val currentChapter = state.chapters.getOrNull(currentEpubChapter)
        val chapterText = currentChapter?.text?.takeIf(String::isNotBlank)
        AiAssistantBottomSheet(
            ttsManager = ttsManager,
            edgeTtsPlayer = edgeTtsPlayer,
            ttsVoice = ttsVoice,
            onSpeakText = speakAssistantText,
            selectionText = state.selection?.text,
            pageLabel = if (isPdf) {
                "Current page · ${state.pdfPageIndex + 1}"
            } else {
                "Current chapter · ${currentChapter?.title ?: "Chapter"}"
            },
            currentPageIndex = (state.pdfPageIndex + 1).takeIf { isPdf },
            pageText = if (isPdf) state.pdfPageText else chapterText,
            chapterTitle = currentChapter?.title,
            chapterText = chapterText,
            isPdf = isPdf,
            isWorking = state.isWorking,
            onSummarizeSelection = viewModel::summarizeSelection,
            onExplainSelection = viewModel::explainSelection,
            onExtractQuote = viewModel::extractMinimalistQuote,
            onSummarizePage = {
                if (isPdf) {
                    viewModel.summarizePdfPage(state.pdfPageIndex)
                } else {
                    viewModel.summarizeChapter(currentEpubChapter)
                }
            },
            onSummarizeChapter = {
                if (!isPdf) viewModel.summarizeChapter(currentEpubChapter)
            },
            onSpeakPage = {
                if (isPdf) {
                    viewModel.readPdfPageText(state.pdfPageIndex) { text ->
                        startSpeech(
                            text,
                            SpeechTarget(
                                chapterIndex = state.pdfPageIndex,
                                chapterTitle = "Page ${state.pdfPageIndex + 1}",
                                sourceOffset = 0,
                                isPdf = true,
                                pageIndex = state.pdfPageIndex,
                                highlightPdfPageText = true,
                            ),
                        )
                    }
                } else {
                    chapterText?.let { text ->
                        startSpeech(
                            text,
                            SpeechTarget(
                                chapterIndex = currentEpubChapter,
                                chapterTitle = currentChapter?.title.orEmpty(),
                                sourceOffset = 0,
                                isPdf = false,
                                pageIndex = currentEpubChapter,
                            ),
                        )
                    }
                }
            },
            onAskBookAi = viewModel::askBookAi,
            aiResult = state.aiResult,
            onDismissAiResult = viewModel::dismissAiResult,
            onDismiss = { assistantVisible = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReaderTopBar(
    title: String,
    foreground: Color,
    background: Color,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onAssistant: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                title,
                color = foreground,
                maxLines = 1,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Text("‹", color = foreground, style = MaterialTheme.typography.headlineMedium)
            }
        },
        actions = {
            IconButton(onClick = onAssistant) {
                Text("AI", color = foreground, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onSettings) {
                Text("Aa", color = foreground, fontWeight = FontWeight.SemiBold)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = background,
            titleContentColor = foreground,
            navigationIconContentColor = foreground,
            actionIconContentColor = foreground,
        ),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReaderBottomBar(
    currentPageLabel: String,
    isPdf: Boolean,
    pdfCurrentPage: Int,
    pdfTotalPages: Int,
    isHorizontalEpub: Boolean,
    epubCurrentPage: Int,
    epubTotalPages: Int,
    verticalProgress: Float,
    showVerticalProgress: Boolean,
    chapterCount: Int,
    currentChapter: Int,
    foreground: Color,
    background: Color,
    onPdfPageChange: (Int) -> Unit,
    onEpubPageChange: (Int) -> Unit,
    onVerticalProgressChange: (Float) -> Unit,
    onChapterChange: (Int) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val inactiveTrackColor = foreground.copy(alpha = 0.12f)
    val activeTrackColor = foreground.copy(alpha = 0.44f)
    val sliderColors = SliderDefaults.colors(
        thumbColor = foreground.copy(alpha = 0.78f),
        activeTrackColor = activeTrackColor,
        inactiveTrackColor = inactiveTrackColor,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledThumbColor = foreground.copy(alpha = 0.3f),
        disabledActiveTrackColor = foreground.copy(alpha = 0.18f),
        disabledInactiveTrackColor = foreground.copy(alpha = 0.07f),
    )
    val showPdfProgress = isPdf && pdfTotalPages > 1
    val showEpubPageProgress = !isPdf && isHorizontalEpub && epubTotalPages > 1
    val showSlider = showPdfProgress || showEpubPageProgress || showVerticalProgress
    val maxValue = when {
        showPdfProgress -> (pdfTotalPages - 1).coerceAtLeast(1).toFloat()
        showEpubPageProgress -> (epubTotalPages - 1).coerceAtLeast(1).toFloat()
        showVerticalProgress -> 1f
        else -> 0f
    }
    val currentValue = when {
        showPdfProgress -> pdfCurrentPage.toFloat()
            .coerceIn(0f, (pdfTotalPages - 1).coerceAtLeast(0).toFloat())
        showEpubPageProgress -> epubCurrentPage.toFloat()
            .coerceIn(0f, (epubTotalPages - 1).coerceAtLeast(0).toFloat())
        showVerticalProgress -> verticalProgress.coerceIn(0f, 1f)
        else -> 0f
    }
    val stepCount = when {
        showPdfProgress -> (pdfTotalPages - 2).coerceAtLeast(0)
        showEpubPageProgress -> (epubTotalPages - 2).coerceAtLeast(0)
        else -> 0
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 24.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = currentPageLabel,
            color = foreground.copy(alpha = 0.58f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 1.dp),
        )
        if (showSlider) {
            Slider(
                value = currentValue,
                onValueChange = { value ->
                    when {
                        showPdfProgress -> onPdfPageChange(value.roundToInt())
                        showEpubPageProgress -> onEpubPageChange(value.roundToInt())
                        showVerticalProgress -> onVerticalProgressChange(value.coerceIn(0f, 1f))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                enabled = maxValue > 0f,
                valueRange = 0f..maxValue,
                steps = stepCount,
                interactionSource = interactionSource,
                colors = sliderColors,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        colors = sliderColors,
                        enabled = maxValue > 0f,
                        thumbSize = DpSize(10.dp, 10.dp),
                    )
                },
                track = { sliderState ->
                    ReadingProgressTrack(
                        fraction = if (showVerticalProgress) currentValue else sliderState.coercedValueAsFraction,
                        activeColor = activeTrackColor,
                        inactiveColor = inactiveTrackColor,
                    )
                },
            )
        }
    }
}

@Composable
private fun ReadingProgressTrack(
    fraction: Float,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        val centerY = size.height / 2f
        val strokeWidth = 2.dp.toPx()
        drawLine(
            color = inactiveColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        val activeEnd = size.width * fraction.coerceIn(0f, 1f)
        if (activeEnd > 0f) {
            drawLine(
                color = activeColor,
                start = Offset(0f, centerY),
                end = Offset(activeEnd, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun copyReaderText(context: Context, text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Reader selection", trimmed))
}

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return currentContext as? Activity
}

private const val TTS_PREFERENCES = "reader_audio_preferences"
private const val TTS_VOICE_KEY = "selected_tts_voice"

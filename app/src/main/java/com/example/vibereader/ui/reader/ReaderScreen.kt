package com.example.vibereader.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.PaddingValues
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.room.BookChapterEntity
import com.example.vibereader.core.theme.ThemeOption
import com.example.vibereader.model.ReaderTtsVoice
import com.example.vibereader.model.ReadingTheme
import com.example.vibereader.util.FreeAiTtsPlayer
import com.example.vibereader.util.SpeechProgress
import com.example.vibereader.util.TtsManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit = {},
    appTheme: ThemeOption = ThemeOption.SYSTEM,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val database = remember(context) { VibeReaderDatabase.getInstance(context) }
    val readerViewModel: ReaderViewModel = viewModel(
        factory = remember(context, database) {
            ReaderViewModel.Factory(context.applicationContext, database)
        },
    )
    val state by readerViewModel.uiState.collectAsState()
    var controlsVisible by remember(bookId) { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    var assistantVisible by remember { mutableStateOf(false) }
    var targetLanguage by remember(bookId) { mutableStateOf("") }
    val voicePreferences = remember(context) {
        context.getSharedPreferences(TTS_PREFERENCES, Context.MODE_PRIVATE)
    }
    var ttsVoice by remember(voicePreferences) {
        mutableStateOf(
            ReaderTtsVoice.fromStoredName(
                voicePreferences.getString(TTS_VOICE_KEY, null),
            ),
        )
    }
    val ttsManager = remember(context) { TtsManager(context) }
    val edgeTtsPlayer = remember(context) { FreeAiTtsPlayer(context) }
    val androidSpeechProgress by ttsManager.speechProgress.collectAsState()
    val edgeSpeechProgress by edgeTtsPlayer.speechProgress.collectAsState()
    val activeSpeechProgress = if (
        ttsVoice == ReaderTtsVoice.ANDROID_SYSTEM ||
        edgeTtsPlayer.isUsingNativeFallback.value
    ) {
        androidSpeechProgress
    } else {
        edgeSpeechProgress
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var pdfJumpRequest by remember(bookId) { mutableIntStateOf(-1) }
    var currentEpubChapter by remember(bookId) { mutableIntStateOf(0) }
    var speechTarget by remember(bookId) { mutableStateOf<SpeechTarget?>(null) }

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
            readerViewModel.readPdfPageText(selected.chapterIndex) { pageText ->
                val selectionOffset = pageText.indexOf(text.trim(), ignoreCase = true)
                startSpeech(
                    text,
                    SpeechTarget(
                        chapterIndex = selected.chapterIndex,
                        chapterTitle = selected.chapterTitle,
                        sourceOffset = selectionOffset.coerceAtLeast(0) + trimmedPrefixLength,
                        isPdf = true,
                        pageIndex = selected.chapterIndex,
                        highlightPdfPageText = selectionOffset >= 0,
                    ),
                )
            }
        } else {
            startSpeech(
                text,
                SpeechTarget(
                    chapterIndex = selected?.chapterIndex ?: currentEpubChapter,
                    chapterTitle = selected?.chapterTitle
                        ?: state.chapters.getOrNull(currentEpubChapter)?.title.orEmpty(),
                    sourceOffset = (selected?.startOffset ?: 0) + trimmedPrefixLength,
                    isPdf = isPdf,
                    pageIndex = state.pdfPageIndex,
                ),
            )
        }
    }
    DisposableEffect(ttsManager) {
        onDispose { ttsManager.shutdown() }
    }
    DisposableEffect(edgeTtsPlayer) {
        onDispose { edgeTtsPlayer.release() }
    }
    LaunchedEffect(bookId, ttsManager) {
        ttsManager.stop()
    }
    DisposableEffect(activity, view) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(activity, view, controlsVisible) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        if (controlsVisible) {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(bookId, readerViewModel) {
        readerViewModel.loadBook(bookId)
    }
    LaunchedEffect(speechTarget, activeSpeechProgress) {
        val target = speechTarget
        if (target?.highlightPdfPageText == true && activeSpeechProgress.isActive) {
            readerViewModel.updatePdfSpeechHighlight(
                pageIndex = target.pageIndex,
                start = activeSpeechProgress.startCharIndex,
                end = activeSpeechProgress.endCharIndex,
            )
        } else {
            readerViewModel.clearPdfSpeechHighlight()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            readerViewModel.clearMessage()
        }
    }

    val epubListState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.initialEpubChapter
            .coerceIn(0, (state.chapters.size - 1).coerceAtLeast(0)),
        initialFirstVisibleItemScrollOffset = state.initialEpubScrollOffset,
    )
    LaunchedEffect(
        epubListState,
        state.chapters.size,
        state.initialEpubChapter,
        state.initialEpubScrollOffset,
        bookId,
    ) {
        if (state.chapters.isNotEmpty()) {
            currentEpubChapter = state.initialEpubChapter.coerceIn(0, state.chapters.lastIndex)
            if (state.settings.scrollMode == ScrollMode.VERTICAL) {
                epubListState.scrollToItem(currentEpubChapter, state.initialEpubScrollOffset)
            }
        }
    }
    LaunchedEffect(epubListState, state.chapters.size, state.book?.format, state.settings.scrollMode, bookId) {
        if (
            state.book?.format.equals("PDF", ignoreCase = true) ||
            state.settings.scrollMode != ScrollMode.VERTICAL
        ) return@LaunchedEffect
        snapshotFlow {
            val layout = epubListState.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull()
            val chapterIndex = (first?.index ?: 0)
                .coerceIn(0, (state.chapters.size - 1).coerceAtLeast(0))
            val itemOffset = first?.offset?.let { -it }?.coerceAtLeast(0) ?: 0
            val fraction = if (layout.totalItemsCount > 0) {
                (chapterIndex + 0.5f) / state.chapters.size.coerceAtLeast(1)
            } else {
                0f
            }
            Triple(chapterIndex, itemOffset, fraction.coerceIn(0f, 1f))
        }
            .distinctUntilChanged()
            .collect { (chapterIndex, itemOffset, fraction) ->
                if (state.chapters.isNotEmpty()) {
                    currentEpubChapter = chapterIndex
                    readerViewModel.updateEpubProgress(chapterIndex, itemOffset, fraction)
                }
            }
    }
    val (background, foreground) = when (appTheme) {
        ThemeOption.OLED_BLACK -> Color.Black to Color.White
        ThemeOption.SEPIA -> Color(0xFFF8F1E5) to Color(0xFF2D2D2D)
        ThemeOption.LIGHT -> Color.White to Color.Black
        ThemeOption.SYSTEM -> MaterialTheme.colorScheme.background to
            MaterialTheme.colorScheme.onBackground
    }
    val systemDarkTheme = isSystemInDarkTheme()
    val (readerBackground, readerForeground) = when (state.settings.readingTheme) {
        ReadingTheme.SYSTEM -> if (systemDarkTheme) {
            Color(0xFF121212) to Color.White
        } else {
            Color.White to Color.Black
        }
        ReadingTheme.OLED_BLACK -> Color.Black to Color.White
        ReadingTheme.SEPIA -> Color(0xFFF8F1E5) to Color(0xFF2D2D2D)
        ReadingTheme.LIGHT -> Color.White to Color.Black
    }

    Scaffold(
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = controlsVisible && !state.isLoading && state.error == null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                ReaderTopBar(
                    title = state.book?.title ?: "Reader",
                    foreground = foreground,
                    background = background,
                    onBack = onBack,
                    onSettings = { settingsVisible = true },
                    onAssistant = { assistantVisible = true },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = controlsVisible && !state.isLoading && state.error == null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                ReaderBottomBar(
                    currentPage = if (state.book?.format.equals("PDF", ignoreCase = true)) {
                        state.pdfPageIndex
                    } else {
                        currentEpubChapter
                    },
                    totalPages = if (state.book?.format.equals("PDF", ignoreCase = true)) {
                        state.pdfPageCount
                    } else {
                        state.chapters.size
                    },
                    foreground = foreground,
                    background = background,
                    onPageChange = { page ->
                        if (state.book?.format.equals("PDF", ignoreCase = true)) {
                            pdfJumpRequest = page
                            readerViewModel.setPdfPageIndex(page)
                        } else {
                            currentEpubChapter = page
                            coroutineScope.launch {
                                if (state.settings.scrollMode == ScrollMode.VERTICAL) {
                                    epubListState.animateScrollToItem(
                                        page.coerceIn(0, (state.chapters.size - 1).coerceAtLeast(0)),
                                    )
                                }
                                readerViewModel.updateEpubProgress(
                                    page,
                                    0,
                                    (page + 0.5f) / state.chapters.size.coerceAtLeast(1),
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(contentPadding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = foreground,
                    )
                    state.error != null -> Text(
                        text = state.error.orEmpty(),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = foreground,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    state.book?.format.equals("PDF", ignoreCase = true) -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(readerBackground),
                        ) {
                            PdfReaderCanvas(
                                bookId = bookId,
                                pageCount = state.pdfPageCount,
                                currentPage = state.pdfPageIndex,
                                scrollMode = state.settings.scrollMode,
                                pageBitmaps = state.pdfPageBitmaps,
                                savedHighlights = state.savedPdfHighlights,
                                aiProcessingPageIndex = state.aiProcessingPageIndex,
                                title = state.book?.title.orEmpty(),
                                theme = state.settings.readingTheme,
                                selection = state.selection,
                                speechSelection = state.speechHighlight,
                                modifier = Modifier.fillMaxSize(),
                                jumpToPageRequest = pdfJumpRequest.takeIf { it >= 0 },
                                onPageChanged = readerViewModel::setPdfPageIndex,
                                onRenderPage = { pageIndex, width, height, zoomScale ->
                                    readerViewModel.requestPdfPageRender(
                                        pageIndex,
                                        width,
                                        height,
                                        zoomScale,
                                    )
                                },
                                onCanvasTap = { controlsVisible = !controlsVisible },
                                onTextLongPress = readerViewModel::selectPdfTextAt,
                                onSelectionHandleDragEnd = readerViewModel::movePdfSelectionHandle,
                            )
                        }
                    }
                    else -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(readerBackground),
                    ) {
                        EpubReaderCanvas(
                            chapters = state.chapters,
                            listState = epubListState,
                            settings = state.settings,
                            currentChapterIndex = currentEpubChapter,
                            foreground = readerForeground,
                            background = readerBackground,
                            speechHighlightStart = speechTarget
                                ?.takeIf {
                                    !it.isPdf && it.chapterIndex == currentEpubChapter &&
                                        activeSpeechProgress.isActive
                                }
                                ?.let { it.sourceOffset + activeSpeechProgress.startCharIndex },
                            speechHighlightEnd = speechTarget
                                ?.takeIf {
                                    !it.isPdf && it.chapterIndex == currentEpubChapter &&
                                        activeSpeechProgress.isActive
                                }
                                ?.let { it.sourceOffset + activeSpeechProgress.endCharIndex },
                            selection = state.selection,
                            onCanvasTap = { controlsVisible = !controlsVisible },
                            onChapterChanged = { chapterIndex ->
                                currentEpubChapter = chapterIndex
                                readerViewModel.updateEpubProgress(
                                    chapterIndex,
                                    0,
                                    (chapterIndex + 0.5f) / state.chapters.size.coerceAtLeast(1),
                                )
                            },
                            onSelectionChanged = readerViewModel::setSelection,
                            selectionVersion = state.selectionVersion,
                        )
                    }
                }

                if (state.selection != null && !state.isLoading) {
                    TextSelectionPopup(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        isWorking = state.isWorking,
                        foreground = readerForeground,
                        background = readerBackground,
                        onSaveQuote = readerViewModel::saveSelectedQuote,
                        onHighlight = readerViewModel::saveHighlight,
                    )
                }
            }
        }
    }

    if (settingsVisible) {
        ReaderSettingsBottomSheet(
            settings = state.settings,
            isPdf = state.book?.format.equals("PDF", ignoreCase = true),
            onSettingsChange = readerViewModel::updateSettings,
            onScrollModeChange = readerViewModel::setScrollMode,
            ttsVoice = ttsVoice,
            onTtsVoiceChange = { voice ->
                ttsVoice = voice
                voicePreferences.edit().putString(TTS_VOICE_KEY, voice.name).apply()
                edgeTtsPlayer.stop()
                ttsManager.stop()
            },
            onDismiss = { settingsVisible = false },
        )
    }

    if (assistantVisible) {
        val isPdf = state.book?.format.equals("PDF", ignoreCase = true)
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
            onSummarizeSelection = readerViewModel::summarizeSelection,
            onExplainSelection = readerViewModel::explainSelection,
            targetLanguage = targetLanguage,
            onTargetLanguageChange = { targetLanguage = it },
            onTranslateSelection = readerViewModel::translateSelection,
            onTranslatePage = { language ->
                if (isPdf) {
                    readerViewModel.translatePdfPage(state.pdfPageIndex, language)
                } else {
                    readerViewModel.translateChapter(currentEpubChapter, language)
                }
            },
            onTranslateChapter = { language ->
                readerViewModel.translateChapter(currentEpubChapter, language)
            },
            onExtractQuote = readerViewModel::extractMinimalistQuote,
            onSummarizePage = {
                if (isPdf) {
                    readerViewModel.summarizePdfPage(state.pdfPageIndex)
                } else {
                    readerViewModel.summarizeChapter(currentEpubChapter)
                }
            },
            onSummarizeChapter = {
                readerViewModel.summarizeChapter(currentEpubChapter)
            },
            onSpeakPage = {
                if (isPdf) {
                    readerViewModel.readPdfPageText(state.pdfPageIndex) { text ->
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
            onDismiss = { assistantVisible = false },
        )
    }

    state.aiResult?.let { result ->
        AlertDialog(
            onDismissRequest = readerViewModel::dismissAiResult,
            title = { Text(result.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = result.content,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    TextButton(onClick = {
                        edgeTtsPlayer.stop()
                        ttsManager.stop()
                        if (ttsVoice == ReaderTtsVoice.ANDROID_SYSTEM) {
                            speechTarget = null
                            ttsManager.speak(result.content)
                        } else {
                            speechTarget = null
                            edgeTtsPlayer.speak(
                                result.content,
                                ttsVoice,
                                ttsManager::speak,
                            )
                        }
                    }) {
                        Text("Listen to result")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = readerViewModel::dismissAiResult) {
                    Text("Done")
                }
            },
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
    androidx.compose.material3.TopAppBar(
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

private const val TTS_PREFERENCES = "reader_audio_preferences"
private const val TTS_VOICE_KEY = "selected_tts_voice"

private data class SpeechTarget(
    val chapterIndex: Int,
    val chapterTitle: String,
    val sourceOffset: Int,
    val isPdf: Boolean,
    val pageIndex: Int,
    val highlightPdfPageText: Boolean = false,
)

@Composable
private fun ReaderBottomBar(
    currentPage: Int,
    totalPages: Int,
    foreground: Color,
    background: Color,
    onPageChange: (Int) -> Unit,
) {
    PageSliderOverlay(
        currentPage = currentPage,
        totalPages = totalPages,
        onPageChange = onPageChange,
        foreground = foreground,
        background = background,
    )
}

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return currentContext as? Activity
}

@Composable
private fun EpubReaderCanvas(
    chapters: List<BookChapterEntity>,
    listState: LazyListState,
    settings: ReaderSettings,
    currentChapterIndex: Int,
    foreground: Color,
    background: Color,
    speechHighlightStart: Int?,
    speechHighlightEnd: Int?,
    selection: ReaderSelection?,
    onCanvasTap: () -> Unit,
    onChapterChanged: (Int) -> Unit,
    onSelectionChanged: (ReaderSelection?) -> Unit,
    selectionVersion: Int,
) {
    val fontSize = settings.fontSizeSp.sp
    val lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp

    if (chapters.isEmpty()) {
        Text(
            text = "Empty Chapter",
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(settings.marginDp.dp),
            color = foreground,
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }

    if (settings.scrollMode == ScrollMode.HORIZONTAL) {
        EpubPager(
            chapterCount = chapters.size,
            currentChapterIndex = currentChapterIndex,
            onChapterChanged = onChapterChanged,
            modifier = Modifier
                .fillMaxSize()
                .background(background),
        ) { chapterIndex ->
            EpubChapterRenderer(
                chapter = chapters[chapterIndex],
                chapterIndex = chapterIndex,
                fontSize = fontSize,
                lineHeight = lineHeight,
                marginDp = settings.marginDp,
                foreground = foreground,
                background = background,
                selectionVersion = selectionVersion,
                highlightStartOffset = speechHighlightStart
                    .takeIf { chapterIndex == currentChapterIndex },
                highlightEndOffset = speechHighlightEnd
                    .takeIf { chapterIndex == currentChapterIndex },
                selection = selection,
                onCanvasTap = onCanvasTap,
                onSelectionChanged = onSelectionChanged,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onCanvasTap() })
            },
        contentPadding = PaddingValues(
            horizontal = settings.marginDp.dp,
            vertical = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(chapters, key = { index, chapter -> "${chapter.bookId}:$index" }) { index, chapter ->
            Text(
                text = chapter.title,
                modifier = Modifier.padding(top = if (index == 0) 0.dp else 24.dp),
                color = foreground,
                style = MaterialTheme.typography.titleLarge,
            )
            if (chapter.text.isBlank()) {
                Text("Empty Chapter", color = foreground, fontSize = fontSize, lineHeight = lineHeight)
            } else {
                EpubSelectableText(
                    chapter = chapter,
                    chapterIndex = index,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    foreground = foreground,
                    selectionVersion = selectionVersion,
                    highlightStartOffset = speechHighlightStart
                        .takeIf { index == currentChapterIndex },
                    highlightEndOffset = speechHighlightEnd
                        .takeIf { index == currentChapterIndex },
                    selection = selection,
                    onSelectionChanged = onSelectionChanged,
                )
            }
        }
    }
}

package com.example.vibereader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vibereader.data.room.BookChapterEntity

/**
 * EPUB rendering engine (Native Compose).
 *
 * - Horizontal mode: consumes the pre-computed, line-bounded [EpubPageSlice] list
 *   from [rememberEpubPages] and drives `HorizontalPager(pageCount = slices.size)`.
 *   Swiping advances exactly one viewport page and never scrolls vertically.
 * - Vertical mode: continuous vertical scrolling with real-time progress.
 */
@Composable
fun EpubReaderCanvas(
    chapters: List<BookChapterEntity>,
    epubPages: List<EpubPageSlice>,
    listState: LazyListState,
    settings: ReaderSettings,
    currentChapterIndex: Int,
    currentEpubPageIndex: Int,
    foreground: Color,
    background: Color,
    speechHighlightStart: Int?,
    speechHighlightEnd: Int?,
    selection: ReaderSelection?,
    onCanvasTap: () -> Unit,
    onChapterChanged: (Int) -> Unit,
    onVerticalScrollProgressChanged: (Float) -> Unit,
    onEpubPageChanged: (pageIndex: Int, chapterIndex: Int, totalPages: Int) -> Unit,
    onSelectionChanged: (ReaderSelection?) -> Unit,
    selectionVersion: Int,
) {
    EpubReaderContent(
        chapters = chapters,
        pageSlices = epubPages,
        settings = settings,
        currentChapterIndex = currentChapterIndex,
        currentEpubPageIndex = currentEpubPageIndex,
        foreground = foreground,
        background = background,
        speechHighlightStart = speechHighlightStart,
        speechHighlightEnd = speechHighlightEnd,
        selection = selection,
        onCanvasTap = onCanvasTap,
        onVerticalScrollProgressChanged = onVerticalScrollProgressChanged,
        onEpubPageChanged = onEpubPageChanged,
        onSelectionChanged = onSelectionChanged,
        selectionVersion = selectionVersion,
    )
}

@Composable
private fun EpubReaderContent(
    chapters: List<BookChapterEntity>,
    pageSlices: List<EpubPageSlice>,
    settings: ReaderSettings,
    currentChapterIndex: Int,
    currentEpubPageIndex: Int,
    foreground: Color,
    background: Color,
    speechHighlightStart: Int?,
    speechHighlightEnd: Int?,
    selection: ReaderSelection?,
    onCanvasTap: () -> Unit,
    onVerticalScrollProgressChanged: (Float) -> Unit,
    onEpubPageChanged: (pageIndex: Int, chapterIndex: Int, totalPages: Int) -> Unit,
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
        val pagingViewModel = remember { EpubViewModel() }
        // A fresh slice list can legitimately restore into a different chapter than
        // the last externally requested one. Skip that one chapter-jump pass and let
        // the page-derived chapter win.
        val skipNextChapterJump = remember { booleanArrayOf(true) }

        // A new slice list means the viewport/settings changed. Keep the relative
        // reading position when that happens; on first load use the saved page hint.
        LaunchedEffect(pageSlices) {
            skipNextChapterJump[0] = true
            if (pageSlices.isEmpty()) {
                pagingViewModel.setCalculatedPages(emptyList(), 0)
                return@LaunchedEffect
            }
            val hadPages = pagingViewModel.totalCalculatedPages > 0
            val previousProgress = if (hadPages) {
                pagingViewModel.progress
            } else {
                (currentEpubPageIndex + 1).toFloat() / pageSlices.size.toFloat()
            }
            pagingViewModel.setCalculatedPages(pageSlices)
            pagingViewModel.restoreFromProgress(
                savedProgress = previousProgress,
                savedPageIndexHint = if (hadPages) -1 else currentEpubPageIndex,
            )
        }

        // External page changes (slider, saved position) drive the pager.
        LaunchedEffect(currentEpubPageIndex, pagingViewModel.totalCalculatedPages) {
            if (pagingViewModel.totalCalculatedPages > 0 &&
                currentEpubPageIndex != pagingViewModel.currentPageIndex
            ) {
                pagingViewModel.onPageChanged(currentEpubPageIndex)
            }
        }

        // Explicit chapter navigation (bottom-bar chapter slider).
        LaunchedEffect(currentChapterIndex, pagingViewModel.allPages) {
            if (skipNextChapterJump[0]) {
                skipNextChapterJump[0] = false
                return@LaunchedEffect
            }
            val current = pagingViewModel.currentSlice ?: return@LaunchedEffect
            if (current.chapterIndex != currentChapterIndex) {
                pagingViewModel.jumpToChapter(currentChapterIndex)
            }
        }

        val allPages = pagingViewModel.allPages
        val totalCalculatedPages = pagingViewModel.totalCalculatedPages
        if (totalCalculatedPages == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Preparing pages…",
                    color = foreground,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return
        }

        val safePageIndex = pagingViewModel.currentPageIndex.coerceIn(0, allPages.lastIndex)

        // Progress is bound to (currentPageIndex + 1) / totalCalculatedPages.
        LaunchedEffect(safePageIndex, totalCalculatedPages) {
            val slice = allPages.getOrNull(safePageIndex) ?: return@LaunchedEffect
            onEpubPageChanged(safePageIndex, slice.chapterIndex, totalCalculatedPages)
        }

        EpubPager(
            pageCount = totalCalculatedPages, // line-bounded slices, NOT chapters.size
            currentPageIndex = safePageIndex,
            onPageChanged = { pageIndex ->
                pagingViewModel.onPageChanged(pageIndex)
                val slice = pagingViewModel.currentSlice ?: return@EpubPager
                onEpubPageChanged(
                    pagingViewModel.currentPageIndex,
                    slice.chapterIndex,
                    totalCalculatedPages,
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val slice = allPages[pageIndex]
            val chapter = chapters.getOrNull(slice.chapterIndex) ?: return@EpubPager
            EpubChapterRenderer(
                chapter = chapter,
                chapterIndex = slice.chapterIndex,
                fontSize = fontSize,
                lineHeight = lineHeight,
                marginDp = settings.marginDp,
                foreground = foreground,
                background = background,
                selectionVersion = selectionVersion,
                highlightStartOffset = speechHighlightStart
                    ?.takeIf { slice.chapterIndex == currentChapterIndex },
                highlightEndOffset = speechHighlightEnd
                    ?.takeIf { slice.chapterIndex == currentChapterIndex },
                selection = selection,
                onCanvasTap = onCanvasTap,
                onSelectionChanged = onSelectionChanged,
                pageSlice = slice,
                showTitle = slice.isChapterStart,
                scrollEnabled = false, // horizontal pages never scroll vertically
            )
        }
        return
    }

    val verticalScrollState = rememberScrollState()
    // Real-time vertical progress: currentScrollPosition / totalScrollRange.
    LaunchedEffect(verticalScrollState.value, verticalScrollState.maxValue) {
        val max = verticalScrollState.maxValue
        val progress = if (max > 0) {
            (verticalScrollState.value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        onVerticalScrollProgressChanged(progress)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) { detectTapGestures(onTap = { onCanvasTap() }) }
            .verticalScroll(verticalScrollState)
            .padding(horizontal = settings.marginDp.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        chapters.forEachIndexed { index, chapter ->
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
                    highlightStartOffset = speechHighlightStart?.takeIf { index == currentChapterIndex },
                    highlightEndOffset = speechHighlightEnd?.takeIf { index == currentChapterIndex },
                    selection = selection,
                    onSelectionChanged = onSelectionChanged,
                )
            }
        }
    }
}

package com.example.vibereader.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vibereader.model.ReadingTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun PdfReaderCanvas(
    bookId: String,
    pageCount: Int,
    currentPage: Int,
    scrollMode: ScrollMode,
    pageBitmaps: Map<Int, Bitmap>,
    savedHighlights: List<com.example.vibereader.data.room.HighlightEntity>,
    aiProcessingPageIndex: Int?,
    title: String,
    theme: ReadingTheme,
    selection: ReaderSelection?,
    speechSelection: ReaderSelection? = null,
    modifier: Modifier = Modifier,
    jumpToPageRequest: Int? = null,
    onPageChanged: (Int) -> Unit,
    onRenderPage: (
        pageIndex: Int,
        requestedWidthPx: Int,
        requestedHeightPx: Int,
        zoomScale: Float,
    ) -> Unit,
    onCanvasTap: () -> Unit,
    onTextLongPress: (pageIndex: Int, xFraction: Float, yFraction: Float) -> Unit,
    onSelectionHandleDrag: (
        pageIndex: Int,
        isStartHandle: Boolean,
        xFraction: Float,
        yFraction: Float,
    ) -> Unit,
    onHighlightTap: (com.example.vibereader.data.room.HighlightEntity) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val listState = rememberLazyListState()
    var zoomedPage by remember(bookId) { mutableStateOf<Int?>(null) }

    fun updateZoom(pageIndex: Int, isZoomed: Boolean) {
        zoomedPage = if (isZoomed) pageIndex else zoomedPage?.takeUnless { it == pageIndex }
    }

    LaunchedEffect(bookId, pageCount, scrollMode) {
        if (pageCount <= 0) return@LaunchedEffect
        val targetPage = currentPage.coerceIn(0, pageCount - 1)

        when (scrollMode) {
            ScrollMode.HORIZONTAL -> {
                pagerState.scrollToPage(targetPage)
                snapshotFlow { pagerState.currentPage }
                    .distinctUntilChanged()
                    .collect(onPageChanged)
            }
            ScrollMode.VERTICAL -> {
                listState.scrollToItem(targetPage)
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .collect(onPageChanged)
            }
        }
    }

    LaunchedEffect(jumpToPageRequest, scrollMode, pageCount) {
        val targetPage = jumpToPageRequest?.takeIf { it in 0 until pageCount } ?: return@LaunchedEffect
        when (scrollMode) {
            ScrollMode.HORIZONTAL -> pagerState.animateScrollToPage(targetPage)
            ScrollMode.VERTICAL -> listState.animateScrollToItem(targetPage)
        }
    }

    when (scrollMode) {
        ScrollMode.HORIZONTAL -> HorizontalPager(
            state = pagerState,
            modifier = modifier.fillMaxSize(),
            key = { pageIndex -> "$bookId:$pageIndex" },
            userScrollEnabled = zoomedPage == null,
        ) { pageIndex ->
            PdfPageRenderer(
                pageIndex = pageIndex,
                bitmap = pageBitmaps[pageIndex],
                title = title,
                theme = theme,
                selection = selection,
                speechSelection = speechSelection,
                savedHighlights = savedHighlights,
                aiProcessingPageIndex = aiProcessingPageIndex,
                fitToBounds = true,
                modifier = Modifier.fillMaxSize(),
                onRenderPage = onRenderPage,
                onCanvasTap = onCanvasTap,
                onTextLongPress = onTextLongPress,
                onSelectionHandleDrag = onSelectionHandleDrag,
                onZoomChanged = { zoomScale ->
                    updateZoom(pageIndex, zoomScale > 1f)
                },
                onHighlightTap = onHighlightTap,
            )
        }

        ScrollMode.VERTICAL -> BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = zoomedPage == null,
            ) {
                items(
                    count = pageCount,
                    key = { pageIndex -> "$bookId:$pageIndex" },
                ) { pageIndex ->
                    PdfPageRenderer(
                        pageIndex = pageIndex,
                        bitmap = pageBitmaps[pageIndex],
                        title = title,
                        theme = theme,
                        selection = selection,
                        speechSelection = speechSelection,
                        savedHighlights = savedHighlights,
                        aiProcessingPageIndex = aiProcessingPageIndex,
                        fitToBounds = true,
                        modifier = Modifier.fillMaxWidth().height(maxHeight),
                        onRenderPage = onRenderPage,
                        onCanvasTap = onCanvasTap,
                        onTextLongPress = onTextLongPress,
                        onSelectionHandleDrag = onSelectionHandleDrag,
                        onZoomChanged = { zoomScale ->
                            updateZoom(pageIndex, zoomScale > 1f)
                        },
                        onHighlightTap = onHighlightTap,
                    )
                }
            }
        }
    }
}

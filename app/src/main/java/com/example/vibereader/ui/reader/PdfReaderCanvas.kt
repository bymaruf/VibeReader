package com.example.vibereader.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
    cropPdfWhitespace: Boolean,
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
    // Exact scale of the visible page: the pager must not steal drags while
    // the user is panning inside a pinch-zoomed PDF page.
    var pagerZoomScale by remember(bookId) { mutableFloatStateOf(1f) }
    var zoomResetToken by remember(bookId) { mutableIntStateOf(0) }

    fun updateZoom(pageIndex: Int, zoomScale: Float) {
        if (pageIndex == pagerState.currentPage) {
            pagerZoomScale = zoomScale
        }
        zoomedPage = if (zoomScale > 1.05f) pageIndex else zoomedPage?.takeUnless { it == pageIndex }
    }

    LaunchedEffect(pagerState.settledPage) {
        // A settled page always starts clean: clear the global pager gate and
        // bump the token so every page's local scale/offset resets to defaults.
        pagerZoomScale = 1f
        zoomedPage = null
        zoomResetToken++
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The user's navigation setting is authoritative in every orientation.
        // Horizontal (Swipe) must stay a HorizontalPager in landscape too; the
        // page itself is fit inside the viewport by PdfPageRenderer.
        val useVerticalPages = scrollMode == ScrollMode.VERTICAL
        // Contrasting separator between pages in vertical mode. Each color is
        // chosen against its own page canvas so the boundary is always visible.
        val pageDividerColor = when (theme) {
            ReadingTheme.OLED_BLACK -> Color(0xFF2C2C2E)
            ReadingTheme.SEPIA -> Color(0xFFD8CCB8)
            ReadingTheme.LIGHT -> Color(0xFFD1D1D6)
            ReadingTheme.SYSTEM -> if (isSystemInDarkTheme()) {
                Color(0xFF3A3A3C)
            } else {
                Color(0xFFD1D1D6)
            }
        }
        // Container-level zoom for vertical reading: the whole list viewport is
        // transformed together, so a zoomed page cannot clip inside its own
        // fixed-height item box.
        var verticalZoomScale by remember(bookId, useVerticalPages) { mutableFloatStateOf(1f) }
        var verticalZoomOffsetX by remember(bookId, useVerticalPages) { mutableFloatStateOf(0f) }

        LaunchedEffect(bookId, pageCount, useVerticalPages) {
            if (pageCount <= 0) return@LaunchedEffect
            val targetPage = currentPage.coerceIn(0, pageCount - 1)
            if (useVerticalPages) {
                listState.scrollToItem(targetPage)
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .collect(onPageChanged)
            } else {
                pagerState.scrollToPage(targetPage)
                snapshotFlow { pagerState.currentPage }
                    .distinctUntilChanged()
                    .collect(onPageChanged)
            }
        }

        LaunchedEffect(jumpToPageRequest, useVerticalPages, pageCount) {
            val targetPage = jumpToPageRequest?.takeIf { it in 0 until pageCount }
                ?: return@LaunchedEffect
            if (useVerticalPages) {
                listState.animateScrollToItem(targetPage)
            } else {
                pagerState.animateScrollToPage(targetPage)
            }
        }

        if (useVerticalPages) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(bookId, useVerticalPages) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val isMultiTouch =
                                    event.changes.count { it.pressed } > 1
                                if (isMultiTouch) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    if (zoom.isFinite() && pan.x.isFinite()) {
                                        val nextScale = (verticalZoomScale * zoom)
                                            .coerceIn(1f, VERTICAL_MAX_SCALE)
                                        val maxX = size.width * (nextScale - 1f) / 2f
                                        verticalZoomScale = nextScale
                                        verticalZoomOffsetX =
                                            (verticalZoomOffsetX + pan.x)
                                                .coerceIn(-maxX, maxX)
                                        event.changes.forEach { change ->
                                            if (change.position != change.previousPosition) {
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = verticalZoomScale
                            scaleY = verticalZoomScale
                            translationX = verticalZoomOffsetX
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    userScrollEnabled = zoomedPage == null,
                ) {
                    items(
                        count = pageCount,
                        key = { pageIndex -> "$bookId:$pageIndex" },
                    ) { pageIndex ->
                        val pageBitmap = pageBitmaps[pageIndex]
                        val pageAspectRatio = pageBitmap
                            ?.takeIf { it.height > 0 }
                            ?.let { it.width.toFloat() / it.height.toFloat() }
                            ?.takeIf { it.isFinite() && it > 0f }
                            ?: DEFAULT_PDF_PAGE_ASPECT_RATIO
                        Column(modifier = Modifier.fillMaxWidth()) {
                            PdfPageRenderer(
                                pageIndex = pageIndex,
                                bitmap = pageBitmap,
                                title = title,
                                theme = theme,
                                cropPdfWhitespace = cropPdfWhitespace,
                                selection = selection,
                                speechSelection = speechSelection,
                                savedHighlights = savedHighlights,
                                aiProcessingPageIndex = aiProcessingPageIndex,
                                // The page slot is sized to the page's own aspect
                                // ratio, so Fit never letterboxes inside a taller
                                // viewport and the 8dp gap stays constant.
                                fitToBounds = true,
                                modifier = Modifier.fillMaxWidth().aspectRatio(pageAspectRatio),
                                onRenderPage = onRenderPage,
                                onCanvasTap = onCanvasTap,
                                onTextLongPress = onTextLongPress,
                                onSelectionHandleDrag = onSelectionHandleDrag,
                                onZoomChanged = { zoomScale ->
                                    updateZoom(pageIndex, zoomScale)
                                },
                                onHighlightTap = onHighlightTap,
                                // Zoom belongs to the whole list viewport here.
                                zoomEnabled = false,
                            )
                            if (pageIndex < pageCount - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    thickness = 1.5.dp,
                                    color = pageDividerColor,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { pageIndex -> "$bookId:$pageIndex" },
                userScrollEnabled = pagerZoomScale <= 1.05f,
            ) { pageIndex ->
                PdfPageRenderer(
                    pageIndex = pageIndex,
                    bitmap = pageBitmaps[pageIndex],
                    title = title,
                    theme = theme,
                    cropPdfWhitespace = cropPdfWhitespace,
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
                        updateZoom(pageIndex, zoomScale)
                    },
                    onHighlightTap = onHighlightTap,
                    zoomResetToken = zoomResetToken,
                )
            }
        }
    }
}

// A4 portrait ratio. Used only until a rendered bitmap supplies the real page
// ratio, so loading pages keep a stable height instead of collapsing.
private const val DEFAULT_PDF_PAGE_ASPECT_RATIO = 0.7071f
private const val VERTICAL_MAX_SCALE = 3.5f

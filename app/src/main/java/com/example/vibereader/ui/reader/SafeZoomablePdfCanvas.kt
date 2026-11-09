package com.example.vibereader.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable
fun SafeZoomablePdfCanvas(
    pageIndex: Int,
    bitmap: Bitmap,
    contentDescription: String,
    colorFilter: ColorFilter?,
    selection: ReaderSelection?,
    selectionRects: List<PdfSelectionRect>,
    savedHighlights: List<com.example.vibereader.data.room.HighlightEntity>,
    showProcessingBoundary: Boolean,
    fitToBounds: Boolean,
    modifier: Modifier = Modifier,
    onCanvasTap: () -> Unit,
    onTextLongPress: (pageIndex: Int, xFraction: Float, yFraction: Float) -> Unit,
    onSelectionHandleDrag: (
        pageIndex: Int,
        isStartHandle: Boolean,
        xFraction: Float,
        yFraction: Float,
    ) -> Unit,
    onHighlightTap: (com.example.vibereader.data.room.HighlightEntity) -> Unit,
    onZoomChanged: (Float) -> Unit,
) {
    var scale by remember(pageIndex) { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember(pageIndex) { mutableStateOf(Offset.Zero) }

    DisposableEffect(pageIndex) {
        onDispose { onZoomChanged(MIN_SCALE) }
    }

    Box(
        modifier = modifier.then(
            if (fitToBounds) Modifier.fillMaxSize() else Modifier.fillMaxWidth().wrapContentHeight(),
        )
            .clipToBounds()
            .pointerInput(pageIndex) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }
                        if (
                            (pressedPointers > 1 || scale > MIN_SCALE) &&
                            event.changes.none { it.isConsumed }
                        ) {
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            if (centroid.x.isFinite() && centroid.y.isFinite()) {
                                val nextScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val nextOffset = if (nextScale <= MIN_SCALE) {
                                    Offset.Zero
                                } else {
                                    val anchoredOffset =
                                        centroid - (centroid - offset - center) * zoom - center
                                    clampOffset(anchoredOffset + pan, nextScale, size)
                                }
                                scale = nextScale
                                offset = nextOffset
                                onZoomChanged(nextScale)
                                event.changes.forEach { change ->
                                    if (change.position != change.previousPosition) change.consume()
                                }
                            }
                        }
                    }
                    while (event.changes.any { it.pressed })
                }
            }
    ) {
        Box(
            modifier = if (fitToBounds) Modifier.fillMaxSize() else {
                Modifier.fillMaxWidth().wrapContentHeight()
            }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = if (fitToBounds) ContentScale.Fit else ContentScale.FillWidth,
                colorFilter = colorFilter,
                modifier = if (fitToBounds) Modifier.fillMaxSize() else {
                    Modifier.fillMaxWidth().wrapContentHeight()
                },
            )
            PdfHighlightOverlay(
                highlights = savedHighlights,
                pageWidthPx = bitmap.width,
                pageHeightPx = bitmap.height,
                fitToBounds = fitToBounds,
                modifier = Modifier.fillMaxSize(),
            )
            if (showProcessingBoundary) {
                Canvas(Modifier.fillMaxSize()) {
                    val contentScale = if (fitToBounds) {
                        minOf(size.width / bitmap.width, size.height / bitmap.height)
                    } else {
                        size.width / bitmap.width
                    }
                    val contentWidth = bitmap.width * contentScale
                    val contentHeight = bitmap.height * contentScale
                    drawRoundRect(
                        color = Color(0xCC6750A4),
                        topLeft = Offset(
                            (size.width - contentWidth) / 2f + 2.dp.toPx(),
                            (size.height - contentHeight) / 2f + 2.dp.toPx(),
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            contentWidth - 4.dp.toPx(),
                            contentHeight - 4.dp.toPx(),
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }
        PdfTextSelectionCanvas(
            modifier = Modifier.matchParentSize(),
            pageIndex = pageIndex,
            scale = scale,
            offset = offset,
            pageWidthPx = bitmap.width,
            pageHeightPx = bitmap.height,
            fitToBounds = fitToBounds,
            selection = selection,
            savedHighlights = savedHighlights,
            onCanvasTap = onCanvasTap,
            onDoubleTap = {
                val nextScale = if (scale > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE
                scale = nextScale
                offset = Offset.Zero
                onZoomChanged(nextScale)
            },
            onTextLongPress = onTextLongPress,
            onSelectionHandleDrag = onSelectionHandleDrag,
            onHighlightTap = onHighlightTap,
        )
        if (selectionRects.isNotEmpty() || selection?.pdfStartHandle != null) {
            PdfSelectionOverlay(
                selection = selection,
                highlightRects = selectionRects,
                pageWidthPx = bitmap.width,
                pageHeightPx = bitmap.height,
                fitToBounds = fitToBounds,
                zoomScale = scale,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

private fun clampOffset(offset: Offset, scale: Float, size: IntSize): Offset {
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f
private const val DOUBLE_TAP_SCALE = 2.5f

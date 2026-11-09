package com.example.vibereader.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.example.vibereader.data.room.HighlightEntity

@Composable
fun PdfTextSelectionCanvas(
    pageIndex: Int,
    scale: Float,
    offset: Offset,
    pageWidthPx: Int,
    pageHeightPx: Int,
    fitToBounds: Boolean,
    selection: ReaderSelection?,
    savedHighlights: List<HighlightEntity>,
    onCanvasTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onTextLongPress: (pageIndex: Int, xFraction: Float, yFraction: Float) -> Unit,
    onSelectionHandleDrag: (
        pageIndex: Int,
        isStartHandle: Boolean,
        xFraction: Float,
        yFraction: Float,
    ) -> Unit,
    onHighlightTap: (HighlightEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(offset)
    val currentSelection by rememberUpdatedState(selection)
    val currentOnCanvasTap by rememberUpdatedState(onCanvasTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnTextLongPress by rememberUpdatedState(onTextLongPress)
    val currentOnSelectionHandleDrag by rememberUpdatedState(onSelectionHandleDrag)
    val currentOnHighlightTap by rememberUpdatedState(onHighlightTap)

    var isDraggingHandle by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    LaunchedEffect(selection, isDraggingHandle) {
        if (isDraggingHandle && selection != null) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val highlightHits = remember(savedHighlights) {
        savedHighlights.map { highlight ->
            highlight to parsePdfHighlightRects(highlight.boundsJson)
        }
    }
    val currentHighlightHits by rememberUpdatedState(highlightHits)

    val density = LocalDensity.current
    val stemLengthPx = with(density) { PdfSelectionHandleMetrics.STEM_LENGTH_DP.dp.toPx() }
    val handleTouchRadiusPx = with(density) { PdfSelectionHandleMetrics.TOUCH_RADIUS_DP.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(pageIndex) {
                detectTapGestures(
                    onTap = { touch ->
                        val fraction = fractionAt(
                            touch = touch,
                            canvasWidthPx = size.width.toFloat(),
                            canvasHeightPx = size.height.toFloat(),
                            scale = currentScale,
                            pan = currentOffset,
                            pageWidthPx = pageWidthPx,
                            pageHeightPx = pageHeightPx,
                            fitToBounds = fitToBounds,
                        )
                        val hit = fraction?.let { point ->
                            currentHighlightHits.firstOrNull { (_, rects) ->
                                hitTestPdfSelectionRects(rects, point.x, point.y)
                            }?.first
                        }
                        if (hit != null) {
                            currentOnHighlightTap(hit)
                        } else {
                            currentOnCanvasTap()
                        }
                    },
                    onDoubleTap = { currentOnDoubleTap() },
                    onLongPress = { touch ->
                        val fraction = fractionAt(
                            touch = touch,
                            canvasWidthPx = size.width.toFloat(),
                            canvasHeightPx = size.height.toFloat(),
                            scale = currentScale,
                            pan = currentOffset,
                            pageWidthPx = pageWidthPx,
                            pageHeightPx = pageHeightPx,
                            fitToBounds = fitToBounds,
                        ) ?: return@detectTapGestures
                        currentOnTextLongPress(pageIndex, fraction.x, fraction.y)
                    },
                )
            }
            .pointerInput(pageIndex) {
                // Inner pointer handler: grab a selection pin before the tap
                // detector sees the down event. It reports every drag update so
                // the pin follows the finger without a release/restart cycle.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val viewport = pdfPageViewport(
                        canvasWidthPx = size.width.toFloat(),
                        canvasHeightPx = size.height.toFloat(),
                        pageWidthPx = pageWidthPx,
                        pageHeightPx = pageHeightPx,
                        fitToBounds = fitToBounds,
                    ) ?: return@awaitEachGesture
                    val scaleNow = currentScale.takeIf { it.isFinite() && it > 0f } ?: 1f
                    val downContent = screenToContentPoint(
                        touch = down.position,
                        canvasWidthPx = size.width.toFloat(),
                        canvasHeightPx = size.height.toFloat(),
                        scale = scaleNow,
                        pan = currentOffset,
                    )
                    val current = currentSelection
                    val handle = hitTestPdfSelectionHandle(
                        contentPoint = downContent,
                        viewport = viewport,
                        startHandle = current?.pdfStartHandle,
                        endHandle = current?.pdfEndHandle,
                        stemLengthPx = stemLengthPx / scaleNow,
                        touchRadiusPx = handleTouchRadiusPx / scaleNow,
                    ) ?: return@awaitEachGesture

                    isDraggingHandle = true
                    // Keep the grip offset between the finger and the text
                    // anchor, so touching the lower half of a pin or slightly
                    // beside it does not make the selection jump a line.
                    val anchorPoint = if (handle == PdfSelectionHandleSide.START) {
                        current?.pdfStartHandle
                    } else {
                        current?.pdfEndHandle
                    }
                    val gripOffset = anchorPoint?.let {
                        downContent - viewport.contentPointAt(it.x, it.y)
                    } ?: Offset.Zero

                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.position != change.previousPosition) {
                            val contentPoint = screenToContentPoint(
                                touch = change.position,
                                canvasWidthPx = size.width.toFloat(),
                                canvasHeightPx = size.height.toFloat(),
                                scale = currentScale,
                                pan = currentOffset,
                            )
                            val fraction = viewport.fractionAt(contentPoint - gripOffset)
                            if (fraction != null) {
                                currentOnSelectionHandleDrag(
                                    pageIndex,
                                    handle == PdfSelectionHandleSide.START,
                                    fraction.x,
                                    fraction.y,
                                )
                            }
                            change.consume()
                        }
                    }
                    isDraggingHandle = false
                }
            },
    )
}

private fun fractionAt(
    touch: Offset,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    scale: Float,
    pan: Offset,
    pageWidthPx: Int,
    pageHeightPx: Int,
    fitToBounds: Boolean,
): Offset? {
    if (
        !scale.isFinite() || scale <= 0f ||
        canvasWidthPx <= 0f || canvasHeightPx <= 0f
    ) {
        return null
    }
    val viewport = pdfPageViewport(
        canvasWidthPx = canvasWidthPx,
        canvasHeightPx = canvasHeightPx,
        pageWidthPx = pageWidthPx,
        pageHeightPx = pageHeightPx,
        fitToBounds = fitToBounds,
    ) ?: return null
    val contentPoint = screenToContentPoint(
        touch = touch,
        canvasWidthPx = canvasWidthPx,
        canvasHeightPx = canvasHeightPx,
        scale = scale,
        pan = pan,
    )
    return viewport.fractionAt(contentPoint)
}

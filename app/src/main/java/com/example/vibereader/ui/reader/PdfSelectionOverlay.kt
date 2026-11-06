package com.example.vibereader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun PdfSelectionOverlay(
    selection: ReaderSelection?,
    highlightRects: List<PdfSelectionRect>,
    pageWidthPx: Int,
    pageHeightPx: Int,
    fitToBounds: Boolean,
    onHandleDragEnd: (isStartHandle: Boolean, xFraction: Float, yFraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageWidthPx <= 0 || pageHeightPx <= 0) return
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        if (widthPx <= 0f || heightPx <= 0f) return@BoxWithConstraints
        val pageScale = if (fitToBounds) {
            minOf(widthPx / pageWidthPx, heightPx / pageHeightPx)
        } else {
            widthPx / pageWidthPx
        }
        val pageWidth = pageWidthPx * pageScale
        val pageHeight = pageHeightPx * pageScale
        val left = (widthPx - pageWidth) / 2f
        val top = (heightPx - pageHeight) / 2f

        Canvas(modifier = Modifier.fillMaxSize()) {
            highlightRects.forEach { rect ->
                val rectLeft = left + rect.left.coerceIn(0f, 1f) * pageWidth
                val rectTop = top + rect.top.coerceIn(0f, 1f) * pageHeight
                val rectRight = left + rect.right.coerceIn(0f, 1f) * pageWidth
                val rectBottom = top + rect.bottom.coerceIn(0f, 1f) * pageHeight
                if (rectRight > rectLeft && rectBottom > rectTop) {
                    drawRect(
                        color = Color(0x553B82F6),
                        topLeft = Offset(rectLeft, rectTop),
                        size = androidx.compose.ui.geometry.Size(
                            rectRight - rectLeft,
                            rectBottom - rectTop,
                        ),
                    )
                }
            }
            val handleStemOffset = with(density) { (40.dp / 3f).toPx() }
            listOfNotNull(selection?.pdfStartHandle, selection?.pdfEndHandle).forEach { point ->
                val anchor = Offset(
                    x = left + point.x * pageWidth,
                    y = top + point.y * pageHeight,
                )
                drawLine(
                    color = Color(0xFF3B82F6),
                    start = anchor,
                    end = anchor.copy(y = anchor.y + handleStemOffset),
                    strokeWidth = with(density) { 2.dp.toPx() },
                )
            }
        }

        selection?.pdfStartHandle?.let { point ->
            PdfSelectionHandle(
                point = point,
                isStartHandle = true,
                left = left,
                top = top,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                onHandleDragEnd = onHandleDragEnd,
            )
        }
        selection?.pdfEndHandle?.let { point ->
            PdfSelectionHandle(
                point = point,
                isStartHandle = false,
                left = left,
                top = top,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                onHandleDragEnd = onHandleDragEnd,
            )
        }
    }
}

@Composable
private fun PdfSelectionHandle(
    point: PdfSelectionPoint,
    isStartHandle: Boolean,
    left: Float,
    top: Float,
    pageWidth: Float,
    pageHeight: Float,
    onHandleDragEnd: (isStartHandle: Boolean, xFraction: Float, yFraction: Float) -> Unit,
) {
    val density = LocalDensity.current
    val touchSize = with(density) { 40.dp.toPx() }
    val handleSize = with(density) { 24.dp.toPx() }
    var dragX by remember(point, isStartHandle) { mutableFloatStateOf(0f) }
    var dragY by remember(point, isStartHandle) { mutableFloatStateOf(0f) }
    val handleCenterX = left + point.x * pageWidth + dragX
    val handleCenterY = top + point.y * pageHeight + dragY + touchSize / 3f

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (handleCenterX - touchSize / 2f).roundToInt(),
                    y = (handleCenterY - touchSize / 2f).roundToInt(),
                )
            }
            .size(40.dp)
            .pointerInput(point, isStartHandle) {
                detectDragGestures(
                    onDragEnd = {
                        onHandleDragEnd(
                            isStartHandle,
                            ((point.x * pageWidth + dragX) / pageWidth).coerceIn(0f, 1f),
                            ((point.y * pageHeight + dragY) / pageHeight).coerceIn(0f, 1f),
                        )
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    dragX += amount.x
                    dragY += amount.y
                }
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(with(density) { handleSize.toDp() })
                .background(Color(0xFF3B82F6), CircleShape),
        )
    }
}

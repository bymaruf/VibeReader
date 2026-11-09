package com.example.vibereader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Draws the current PDF text selection and its blue pins. All pointer handling
 * (taps, long-press, handle dragging) lives in PdfTextSelectionCanvas.
 */
@Composable
fun PdfSelectionOverlay(
    selection: ReaderSelection?,
    highlightRects: List<PdfSelectionRect>,
    pageWidthPx: Int,
    pageHeightPx: Int,
    fitToBounds: Boolean,
    zoomScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    if (pageWidthPx <= 0 || pageHeightPx <= 0) return
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val viewport = pdfPageViewport(
            canvasWidthPx = widthPx,
            canvasHeightPx = heightPx,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            fitToBounds = fitToBounds,
        ) ?: return@BoxWithConstraints
        val inverseScale = 1f / zoomScale.coerceAtLeast(0.01f)
        val stemLengthPx =
            with(density) { PdfSelectionHandleMetrics.STEM_LENGTH_DP.dp.toPx() } * inverseScale
        val pinRadiusPx =
            with(density) { PdfSelectionHandleMetrics.RADIUS_DP.dp.toPx() } * inverseScale
        val stemWidthPx = with(density) { 2.dp.toPx() } * inverseScale
        val selectionColor = Color(0xFF3B82F6)

        Canvas(modifier = Modifier.fillMaxSize()) {
            highlightRects.forEach { rect ->
                val contentRect = viewport.contentRect(rect) ?: return@forEach
                drawRect(
                    color = Color(0x553B82F6),
                    topLeft = contentRect.topLeft,
                    size = contentRect.size,
                )
            }

            listOfNotNull(
                selection?.pdfStartHandle,
                selection?.pdfEndHandle,
            ).forEach { point ->
                val anchor = viewport.contentPointAt(point.x, point.y)
                val pinCenter = anchor.copy(y = anchor.y + stemLengthPx)
                drawLine(
                    color = selectionColor,
                    start = anchor,
                    end = pinCenter,
                    strokeWidth = stemWidthPx,
                )
                drawCircle(
                    color = selectionColor,
                    radius = pinRadiusPx,
                    center = pinCenter,
                )
                drawCircle(
                    color = Color.White,
                    radius = pinRadiusPx * 0.35f,
                    center = pinCenter,
                )
            }
        }
    }
}

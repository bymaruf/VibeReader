package com.example.vibereader.ui.reader

import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.json.JSONArray
import org.json.JSONException

private const val TAG = "PdfHighlightOverlay"

/** Parses the normalized rectangles stored in [HighlightEntity.boundsJson]. */
fun parsePdfHighlightRects(boundsJson: String?): List<PdfSelectionRect> {
    if (boundsJson.isNullOrBlank()) return emptyList()
    val array = try {
        JSONArray(boundsJson)
    } catch (error: JSONException) {
        Log.w(TAG, "Ignoring malformed PDF highlight bounds.", error)
        return emptyList()
    }
    val rects = mutableListOf<PdfSelectionRect>()
    for (index in 0 until array.length()) {
        val values = array.optJSONArray(index) ?: continue
        if (values.length() < 4) continue
        val rect = PdfSelectionRect(
            left = values.optDouble(0, Double.NaN).toFloat(),
            top = values.optDouble(1, Double.NaN).toFloat(),
            right = values.optDouble(2, Double.NaN).toFloat(),
            bottom = values.optDouble(3, Double.NaN).toFloat(),
        )
        if (rect.isValid) rects += rect
    }
    return rects
}

/**
 * Visual-only highlight layer. Tap hit testing lives in PdfTextSelectionCanvas,
 * which is the topmost pointer target on the page.
 */
@Composable
fun PdfHighlightOverlay(
    highlights: List<com.example.vibereader.data.room.HighlightEntity>,
    pageWidthPx: Int,
    pageHeightPx: Int,
    fitToBounds: Boolean,
    modifier: Modifier = Modifier,
) {
    if (highlights.isEmpty() || pageWidthPx <= 0 || pageHeightPx <= 0) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val viewport = pdfPageViewport(
            canvasWidthPx = size.width,
            canvasHeightPx = size.height,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            fitToBounds = fitToBounds,
        ) ?: return@Canvas

        highlights.forEach { highlight ->
            val rects = parsePdfHighlightRects(highlight.boundsJson)
            if (rects.isEmpty()) return@forEach
            val color = try {
                AndroidColor.parseColor(highlight.colorHex)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Ignoring invalid PDF highlight color for ${highlight.id}.", error)
                AndroidColor.YELLOW
            }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                alpha = 95
                style = android.graphics.Paint.Style.FILL
            }
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                rects.forEach { rect ->
                    val contentRect = viewport.contentRect(rect) ?: return@forEach
                    nativeCanvas.drawRoundRect(
                        contentRect.left,
                        contentRect.top,
                        contentRect.right,
                        contentRect.bottom,
                        4f,
                        4f,
                        paint,
                    )
                }
            }
        }
    }
}

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

private const val TAG = "PdfHighlightOverlay"

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
        val scale = if (fitToBounds) {
            minOf(size.width / pageWidthPx, size.height / pageHeightPx)
        } else {
            size.width / pageWidthPx
        }
        val renderedWidth = pageWidthPx * scale
        val renderedHeight = pageHeightPx * scale
        val leftOffset = (size.width - renderedWidth) / 2f
        val topOffset = (size.height - renderedHeight) / 2f

        highlights.forEach { highlight ->
            val rects = try {
                JSONArray(highlight.boundsJson.orEmpty())
            } catch (error: org.json.JSONException) {
                Log.w(TAG, "Ignoring malformed PDF highlight bounds for ${highlight.id}.", error)
                return@forEach
            }
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
                for (index in 0 until rects.length()) {
                    val values = rects.optJSONArray(index) ?: continue
                    if (values.length() < 4) continue
                    val left = values.optDouble(0, Double.NaN).toFloat()
                    val top = values.optDouble(1, Double.NaN).toFloat()
                    val right = values.optDouble(2, Double.NaN).toFloat()
                    val bottom = values.optDouble(3, Double.NaN).toFloat()
                    if (
                        !left.isFinite() || !top.isFinite() ||
                        !right.isFinite() || !bottom.isFinite() ||
                        right <= left || bottom <= top
                    ) continue
                    nativeCanvas.drawRoundRect(
                        leftOffset + left.coerceIn(0f, 1f) * renderedWidth,
                        topOffset + top.coerceIn(0f, 1f) * renderedHeight,
                        leftOffset + right.coerceIn(0f, 1f) * renderedWidth,
                        topOffset + bottom.coerceIn(0f, 1f) * renderedHeight,
                        4f,
                        4f,
                        paint,
                    )
                }
            }
        }

    }
}

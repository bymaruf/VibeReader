package com.example.vibereader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A normalized (0..1) point on a rendered PDF page. */
data class PdfSelectionPoint(
    val x: Float,
    val y: Float,
) {
    fun clamped(): PdfSelectionPoint =
        PdfSelectionPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** A normalized (0..1) rectangle on a rendered PDF page. */
data class PdfSelectionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val isValid: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top

    val centerY: Float
        get() = (top + bottom) / 2f

    fun contains(xFraction: Float, yFraction: Float, padding: Float = 0f): Boolean =
        xFraction >= left - padding && xFraction <= right + padding &&
            yFraction >= top - padding && yFraction <= bottom + padding
}

/**
 * Layout of the page image inside the reader canvas. All values are unscaled
 * content pixels (the same coordinate space used by the selection overlay).
 */
data class PdfPageViewport(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun containsContentPoint(point: Offset): Boolean =
        width > 0f && height > 0f &&
            point.x >= left && point.x <= right &&
            point.y >= top && point.y <= bottom

    fun fractionAt(contentPoint: Offset): Offset? {
        if (!containsContentPoint(contentPoint)) return null
        return Offset(
            x = ((contentPoint.x - left) / width).coerceIn(0f, 1f),
            y = ((contentPoint.y - top) / height).coerceIn(0f, 1f),
        )
    }

    fun contentPointAt(xFraction: Float, yFraction: Float): Offset = Offset(
        x = left + xFraction.coerceIn(0f, 1f) * width,
        y = top + yFraction.coerceIn(0f, 1f) * height,
    )

    fun contentRect(rect: PdfSelectionRect): Rect? {
        if (!rect.isValid) return null
        val rectLeft = left + rect.left.coerceIn(0f, 1f) * width
        val rectTop = top + rect.top.coerceIn(0f, 1f) * height
        val rectRight = left + rect.right.coerceIn(0f, 1f) * width
        val rectBottom = top + rect.bottom.coerceIn(0f, 1f) * height
        return Rect(rectLeft, rectTop, rectRight, rectBottom)
    }
}

/** Computes the letterboxed/fitted page area for the current canvas size. */
fun pdfPageViewport(
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    pageWidthPx: Int,
    pageHeightPx: Int,
    fitToBounds: Boolean,
): PdfPageViewport? {
    if (
        !canvasWidthPx.isFinite() || !canvasHeightPx.isFinite() ||
        canvasWidthPx <= 0f || canvasHeightPx <= 0f ||
        pageWidthPx <= 0 || pageHeightPx <= 0
    ) {
        return null
    }
    val pageWidth = pageWidthPx.toFloat()
    val pageHeight = pageHeightPx.toFloat()
    val scale = if (fitToBounds) {
        min(canvasWidthPx / pageWidth, canvasHeightPx / pageHeight)
    } else {
        canvasWidthPx / pageWidth
    }
    if (!scale.isFinite() || scale <= 0f) return null
    val contentWidth = pageWidth * scale
    val contentHeight = pageHeight * scale
    return PdfPageViewport(
        left = (canvasWidthPx - contentWidth) / 2f,
        top = (canvasHeightPx - contentHeight) / 2f,
        width = contentWidth,
        height = contentHeight,
    )
}

/**
 * Converts a touch in canvas coordinates to the unscaled content coordinates
 * used by the rendered page and the selection overlay. The zoom/pan layer is
 * applied around the canvas centre (Compose's default transform origin).
 */
fun screenToContentPoint(
    touch: Offset,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    scale: Float,
    pan: Offset,
): Offset {
    if (!scale.isFinite() || scale <= 0f) return touch
    val centerX = canvasWidthPx / 2f
    val centerY = canvasHeightPx / 2f
    return Offset(
        x = centerX + (touch.x - centerX - pan.x) / scale,
        y = centerY + (touch.y - centerY - pan.y) / scale,
    )
}

/** Inverse of [screenToContentPoint]; useful for drawing screen-sized handles. */
fun contentToScreenPoint(
    contentPoint: Offset,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    scale: Float,
    pan: Offset,
): Offset {
    if (!scale.isFinite() || scale <= 0f) return contentPoint
    val centerX = canvasWidthPx / 2f
    val centerY = canvasHeightPx / 2f
    return Offset(
        x = centerX + (contentPoint.x - centerX) * scale + pan.x,
        y = centerY + (contentPoint.y - centerY) * scale + pan.y,
    )
}

/** Merges glyph rectangles into one rectangle per text line. */
fun mergePdfSelectionRects(rects: List<PdfSelectionRect>): List<PdfSelectionRect> {
    val merged = mutableListOf<PdfSelectionRect>()
    rects.filter(PdfSelectionRect::isValid)
        .groupBy { (it.top * 100f).roundToInt() }
        .toSortedMap()
        .values
        .forEach { lineRects ->
            val sorted = lineRects.sortedBy(PdfSelectionRect::left)
            var current: PdfSelectionRect? = null
            sorted.forEach { rect ->
                val previous = current
                if (previous != null && rect.left <= previous.right + RECT_MERGE_GAP_FRACTION) {
                    current = previous.copy(
                        right = max(previous.right, rect.right),
                        top = min(previous.top, rect.top),
                        bottom = max(previous.bottom, rect.bottom),
                    )
                } else {
                    current?.let(merged::add)
                    current = rect
                }
            }
            current?.let(merged::add)
        }
    return merged
}

fun hitTestPdfSelectionRects(
    rects: List<PdfSelectionRect>,
    xFraction: Float,
    yFraction: Float,
    padding: Float = HIGHLIGHT_HIT_PADDING_FRACTION,
): Boolean = rects.any { it.contains(xFraction, yFraction, padding) }

enum class PdfSelectionHandleSide {
    START,
    END,
}

/**
 * Returns which selection handle is under the touch. The pin is drawn a short
 * stem below the text anchor, so hit testing uses the pin centre, not the
 * anchor itself.
 */
fun hitTestPdfSelectionHandle(
    contentPoint: Offset,
    viewport: PdfPageViewport,
    startHandle: PdfSelectionPoint?,
    endHandle: PdfSelectionPoint?,
    stemLengthPx: Float,
    touchRadiusPx: Float,
): PdfSelectionHandleSide? {
    if (touchRadiusPx <= 0f) return null

    fun distanceTo(point: PdfSelectionPoint?): Float? {
        if (point == null) return null
        val center = viewport.contentPointAt(point.x, point.y) + Offset(0f, stemLengthPx)
        return (contentPoint - center).getDistance()
    }

    val startDistance = distanceTo(startHandle)
    val endDistance = distanceTo(endHandle)
    val startHit = startDistance != null && startDistance <= touchRadiusPx
    val endHit = endDistance != null && endDistance <= touchRadiusPx
    return when {
        startHit && endHit -> {
            if ((startDistance ?: Float.MAX_VALUE) <= (endDistance ?: Float.MAX_VALUE)) {
                PdfSelectionHandleSide.START
            } else {
                PdfSelectionHandleSide.END
            }
        }
        startHit -> PdfSelectionHandleSide.START
        endHit -> PdfSelectionHandleSide.END
        else -> null
    }
}

/** Distance from a word's horizontal span to [xFraction]; 0 when inside. */
fun horizontalDistanceToWord(
    left: Float,
    right: Float,
    xFraction: Float,
): Float = when {
    xFraction < left -> left - xFraction
    xFraction > right -> xFraction - right
    else -> 0f
}

fun verticalDistanceToLine(
    top: Float,
    bottom: Float,
    yFraction: Float,
): Float = when {
    yFraction < top -> top - yFraction
    yFraction > bottom -> yFraction - bottom
    else -> 0f
}

fun distanceBetween(a: PdfSelectionPoint, xFraction: Float, yFraction: Float): Float {
    val dx = a.x - xFraction
    val dy = a.y - yFraction
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

fun rectCenterDistance(rect: PdfSelectionRect, xFraction: Float, yFraction: Float): Float {
    val dx = (rect.left + rect.right) / 2f - xFraction
    val dy = rect.centerY - yFraction
    return abs(dx) + abs(dy)
}

object PdfSelectionHandleMetrics {
    /** Vertical distance from the text anchor down to the pin centre, in dp. */
    const val STEM_LENGTH_DP = 40f / 3f

    /** Drawn pin radius, in dp. */
    const val RADIUS_DP = 12f

    /** Comfortable screen-space touch radius, in dp. */
    const val TOUCH_RADIUS_DP = 24f
}

private const val RECT_MERGE_GAP_FRACTION = 0.015f
private const val HIGHLIGHT_HIT_PADDING_FRACTION = 0.002f

package com.example.vibereader.ui.reader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun calculateFitScale(
    pageWidth: Int,
    pageHeight: Int,
    containerWidth: Int,
    containerHeight: Int,
): Float {
    if (pageWidth <= 0 || pageHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
        return 0f
    }

    val widthScale = containerWidth.toFloat() / pageWidth
    val heightScale = containerHeight.toFloat() / pageHeight
    return minOf(widthScale, heightScale)
}

internal fun renderPageCrisp(
    page: PdfRenderer.Page,
    targetWidth: Int,
    targetHeight: Int,
    zoomScale: Float,
    maxBitmapPixels: Long,
    maxBitmapDimension: Int,
): Bitmap? {
    if (
        targetWidth <= 0 || targetHeight < 0 ||
        !zoomScale.isFinite() || zoomScale < 1f ||
        maxBitmapPixels <= 0L || maxBitmapDimension <= 0 ||
        page.width <= 0 || page.height <= 0
    ) {
        return null
    }

    val fitScale = if (targetHeight > 0) {
        calculateFitScale(page.width, page.height, targetWidth, targetHeight)
    } else {
        targetWidth.toFloat() / page.width
    }
    val requestedScale = fitScale * zoomScale
    val maxScaleForPixels = sqrt(
        maxBitmapPixels.toDouble() / (page.width.toDouble() * page.height),
    ).toFloat()
    val maxScaleForDimension = maxBitmapDimension.toFloat() / maxOf(page.width, page.height)
    val scale = minOf(requestedScale, maxScaleForPixels, maxScaleForDimension)
    if (!scale.isFinite() || scale <= 0f) return null

    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
    var bitmap: Bitmap? = null
    return try {
        val createdBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap = createdBitmap
        createdBitmap.eraseColor(android.graphics.Color.WHITE)
        val matrix = Matrix().apply { setScale(scale, scale) }
        page.render(
            createdBitmap,
            Rect(0, 0, width, height),
            matrix,
            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
        )
        createdBitmap
    } catch (_: OutOfMemoryError) {
        bitmap?.recycle()
        null
    }
}

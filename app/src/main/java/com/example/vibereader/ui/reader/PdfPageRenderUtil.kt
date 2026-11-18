package com.example.vibereader.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "PdfPageRenderUtil"

/**
 * Single-thread serializer for Android [PdfRenderer] access. [PdfRenderer] and
 * its pages are not thread-safe; all `openPage`/`render`/`close` operations for
 * the same renderer must run on this dispatcher (or equivalent serialization)
 * to prevent concurrent thread locking/freezes on scanned/vector PDFs.
 */
internal val PDF_RENDER_DISPATCHER: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(1)

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

/**
 * Renders [page] into a crisp bitmap sized for the requested target.
 *
 * The caller owns [page] lifecycle (`openPage`/`close` in `finally`) and must
 * serialize [PdfRenderer] access (see [PDF_RENDER_DISPATCHER]). Bitmap
 * allocation and native rendering are wrapped in try-catch so memory pressure
 * or a problematic page falls back safely (returns `null`) instead of hanging
 * the UI on an indefinite loading spinner.
 */
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
    if (!fitScale.isFinite() || fitScale <= 0f) return null

    val requestedScale = fitScale * zoomScale
    val pagePixels = page.width.toDouble() * page.height.toDouble()
    val maxScaleForPixels = if (pagePixels > 0.0) {
        sqrt(maxBitmapPixels.toDouble() / pagePixels).toFloat()
    } else {
        0f
    }
    val maxScaleForDimension =
        maxBitmapDimension.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
    val scale = minOf(requestedScale, maxScaleForPixels, maxScaleForDimension)
    if (!scale.isFinite() || scale <= 0f) return null

    val width = (page.width * scale).roundToInt().coerceIn(1, maxBitmapDimension)
    val height = (page.height * scale).roundToInt().coerceIn(1, maxBitmapDimension)
    val pixelCount = width.toLong() * height.toLong()
    if (pixelCount <= 0L || pixelCount > maxBitmapPixels) return null

    var bitmap: Bitmap? = null
    return try {
        val createdBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap = createdBitmap
        createdBitmap.eraseColor(Color.WHITE)
        val matrix = Matrix().apply { setScale(scale, scale) }
        page.render(
            createdBitmap,
            Rect(0, 0, width, height),
            matrix,
            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
        )
        createdBitmap
    } catch (error: OutOfMemoryError) {
        Log.w(TAG, "PDF bitmap OOM (${width}x$height); falling back to preview-safe failure.", error)
        runCatching { bitmap?.recycle() }
        bitmap = null
        // Fall back to a minimal readable preview bitmap when possible, so the
        // page does not hang on an infinite loading spinner.
        createPreviewFallback(page, maxBitmapDimension)
    } catch (error: Throwable) {
        Log.w(TAG, "PDF page render failed; falling back safely.", error)
        runCatching { bitmap?.recycle() }
        null
    }
}

/**
 * Crops the uniform white margins around a rendered PDF page.
 *
 * Sampling is intentionally sparse (every 4th row and every 4th column) so a
 * large page costs only a fraction of a full scan. The crop is rejected when
 * the detected ink rectangle is too small to be real content, which protects
 * against isolated speckles in an otherwise blank page. A 4% safe margin is
 * added around the detected bounds before the bitmap is trimmed.
 *
 * Must run on a background dispatcher. [PdfRenderer] access is unaffected: the
 * bitmap is already fully rendered when this is called.
 */
internal fun cropPdfWhitespaceMargins(bitmap: Bitmap): Bitmap {
    if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) return bitmap
    return try {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var y = 0
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                val pixel = row[x]
                val isInk =
                    Color.red(pixel) < 245 ||
                        Color.green(pixel) < 245 ||
                        Color.blue(pixel) < 245
                if (isInk) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += 4
            }
            y += 4
        }
        if (maxX < 0 || maxY < 0) return bitmap

        val inkWidth = maxX - minX + 1
        val inkHeight = maxY - minY + 1
        if (inkWidth <= width * 0.20f || inkHeight <= height * 0.15f) return bitmap

        val padX = (width * 0.04f).roundToInt().coerceAtLeast(1)
        val padY = (height * 0.04f).roundToInt().coerceAtLeast(1)
        val left = (minX - padX).coerceAtLeast(0)
        val top = (minY - padY).coerceAtLeast(0)
        val right = (maxX + padX + 1).coerceAtMost(width)
        val bottom = (maxY + padY + 1).coerceAtMost(height)
        if (left <= 0 && top <= 0 && right >= width && bottom >= height) return bitmap

        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        if (cropped !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        cropped
    } catch (error: Throwable) {
        Log.w(TAG, "Could not crop PDF whitespace margins; keeping full page.", error)
        bitmap
    }
}

/**
 * Best-effort low-resolution fallback after OOM. Returns `null` if even a small
 * allocation cannot be satisfied.
 */
private fun createPreviewFallback(page: PdfRenderer.Page, maxBitmapDimension: Int): Bitmap? {
    val previewMax = minOf(720, maxBitmapDimension.coerceAtLeast(1))
    val longest = maxOf(page.width, page.height).coerceAtLeast(1)
    val scale = (previewMax.toFloat() / longest.toFloat()).coerceIn(0.05f, 1f)
    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
    var bitmap: Bitmap? = null
    return try {
        val createdBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap = createdBitmap
        createdBitmap.eraseColor(Color.WHITE)
        val matrix = Matrix().apply { setScale(scale, scale) }
        page.render(
            createdBitmap,
            Rect(0, 0, width, height),
            matrix,
            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
        )
        createdBitmap
    } catch (_: Throwable) {
        runCatching { bitmap?.recycle() }
        null
    }
}

/**
 * Opens, renders, and always closes a [PdfRenderer.Page] on the caller's
 * (serialized) execution context. Prefer calling this from
 * [PDF_RENDER_DISPATCHER] when you own the open/render/close sequence.
 */
internal fun renderOpenedPdfPageSafely(
    renderer: PdfRenderer,
    pageIndex: Int,
    targetWidth: Int,
    targetHeight: Int,
    zoomScale: Float,
    maxBitmapPixels: Long,
    maxBitmapDimension: Int,
): Bitmap? {
    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
    var page: PdfRenderer.Page? = null
    return try {
        page = renderer.openPage(pageIndex)
        renderPageCrisp(
            page = page,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            zoomScale = zoomScale,
            maxBitmapPixels = maxBitmapPixels,
            maxBitmapDimension = maxBitmapDimension,
        )
    } catch (error: Throwable) {
        Log.w(TAG, "Safe PDF page open/render failed for index $pageIndex.", error)
        null
    } finally {
        try {
            page?.close()
        } catch (closeError: Throwable) {
            Log.w(TAG, "Failed to close PDF page $pageIndex.", closeError)
        }
    }
}

package com.example.vibereader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.vibereader.domain.QuoteQualityValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object TypographyWallpaperGenerator {
    suspend fun generateWallpaperBitmap(
        context: Context,
        quote: String,
        bookTitle: String,
        progressText: String,
        bookAuthor: String? = null,
    ): Bitmap = withContext(Dispatchers.IO) {
        if (!QuoteQualityValidator.isValidQuote(quote)) {
            throw IOException("The selected quote is incomplete or too short for a wallpaper.")
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: throw IOException("Could not access the device display.")
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) {
            throw IOException("Could not determine the device wallpaper dimensions.")
        }
        val bitmapBytes = width.toLong() * height * BYTES_PER_PIXEL
        if (bitmapBytes > Runtime.getRuntime().maxMemory() / MAX_HEAP_FRACTION) {
            throw IOException("The device display is too large to render safely.")
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        intArrayOf(BACKGROUND_TOP, BACKGROUND_MIDDLE, BACKGROUND_BOTTOM),
                        floatArrayOf(0f, 0.58f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                },
            )

            val horizontalMargin = width * HORIZONTAL_MARGIN_FRACTION
            val textWidth = (width - horizontalMargin * 2).toInt().coerceAtLeast(1)
            val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
                textSize = width * QUOTE_TEXT_SIZE_FRACTION
            }
            val quoteLayout = createFittingQuoteLayout(
                quote = quote.trim(),
                paint = quotePaint,
                width = textWidth,
                maxHeight = (height * MAX_QUOTE_HEIGHT_FRACTION).toInt(),
            )
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TITLE_COLOR
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textSize = width * TITLE_TEXT_SIZE_FRACTION
                textAlign = Paint.Align.CENTER
            }
            val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AUTHOR_COLOR
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textSize = width * AUTHOR_TEXT_SIZE_FRACTION
                textAlign = Paint.Align.CENTER
            }
            val title = ellipsize(bookTitle.ifBlank { "VibeReader" }, titlePaint, textWidth)
            val author = bookAuthor?.takeIf(String::isNotBlank)
                ?.let { ellipsize(it, authorPaint, textWidth) }
            val quoteAndTitleGap = height * QUOTE_TITLE_GAP_FRACTION
            val titleHeight = titlePaint.fontMetrics.run { descent - ascent }
            val authorHeight = if (author == null) 0f else {
                authorPaint.fontMetrics.run { descent - ascent } + height * AUTHOR_GAP_FRACTION
            }
            val groupHeight = quoteLayout.height + quoteAndTitleGap + titleHeight + authorHeight
            var y = (height * QUOTE_CENTER_Y_FRACTION - groupHeight / 2f)
                .coerceAtLeast(height * MIN_CONTENT_TOP_FRACTION)

            canvas.save()
            canvas.translate(horizontalMargin, y)
            quoteLayout.draw(canvas)
            canvas.restore()

            y += quoteLayout.height + quoteAndTitleGap
            canvas.drawText(title, width / 2f, y - titlePaint.fontMetrics.ascent, titlePaint)
            if (author != null) {
                y += titleHeight + height * AUTHOR_GAP_FRACTION
                canvas.drawText(author, width / 2f, y - authorPaint.fontMetrics.ascent, authorPaint)
            }

            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ACCENT_COLOR
                strokeWidth = (width * ACCENT_STROKE_FRACTION).coerceAtLeast(2f)
                strokeCap = Paint.Cap.ROUND
            }
            val accentY = (height * ACCENT_Y_FRACTION)
                .coerceIn(height * MIN_CONTENT_TOP_FRACTION, height * MAX_ACCENT_Y_FRACTION)
            canvas.drawLine(
                width * ACCENT_LEFT_FRACTION,
                accentY,
                width * (1f - ACCENT_LEFT_FRACTION),
                accentY,
                accentPaint,
            )

            val progressPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = PROGRESS_COLOR
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textSize = width * PROGRESS_TEXT_SIZE_FRACTION
                textAlign = Paint.Align.CENTER
            }
            val safeProgressText = ellipsize(progressText, progressPaint, textWidth)
            val progressBaseline = height * PROGRESS_BASELINE_FRACTION
            canvas.drawText(safeProgressText, width / 2f, progressBaseline, progressPaint)
            bitmap
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
    }

    suspend fun saveWallpaperPreview(context: Context, bitmap: Bitmap): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.applicationContext.filesDir, WALLPAPER_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create wallpaper preview storage.")
            }
            val destination = File(directory, WALLPAPER_FILE)
            val temporary = File(directory, "$WALLPAPER_FILE.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        throw IOException("Could not save the wallpaper preview.")
                    }
                    output.fd.sync()
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Could not finalize the wallpaper preview.")
                }
                destination
            } catch (error: Exception) {
                temporary.delete()
                throw error
            }
        }

    private fun createFittingQuoteLayout(
        quote: String,
        paint: TextPaint,
        width: Int,
        maxHeight: Int,
    ): StaticLayout {
        val minTextSize = paint.textSize * MIN_QUOTE_TEXT_SCALE
        var layout = buildQuoteLayout(quote, paint, width)
        while (layout.height > maxHeight && paint.textSize > minTextSize) {
            paint.textSize = (paint.textSize * QUOTE_SHRINK_FACTOR).coerceAtLeast(minTextSize)
            layout = buildQuoteLayout(quote, paint, width)
        }
        return layout
    }

    private fun buildQuoteLayout(quote: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(quote, 0, quote.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, QUOTE_LINE_SPACING_MULTIPLIER)
            .setMaxLines(MAX_QUOTE_LINES)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun ellipsize(text: String, paint: TextPaint, width: Int): String =
        TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END).toString()

    private const val WALLPAPER_DIRECTORY = "wallpaper_studio"
    private const val WALLPAPER_FILE = "current.jpg"
    private const val JPEG_QUALITY = 92
    private const val BYTES_PER_PIXEL = 4L
    private const val MAX_HEAP_FRACTION = 4L
    private const val BACKGROUND_TOP = 0xFF0F172A.toInt()
    private const val BACKGROUND_MIDDLE = 0xFF111827.toInt()
    private const val BACKGROUND_BOTTOM = 0xFF020617.toInt()
    private const val TITLE_COLOR = 0xFFCBD5E1.toInt()
    private const val AUTHOR_COLOR = 0xFF94A3B8.toInt()
    private const val PROGRESS_COLOR = 0xFF64748B.toInt()
    private const val ACCENT_COLOR = 0xFF38BDF8.toInt()
    private const val HORIZONTAL_MARGIN_FRACTION = 0.1f
    private const val QUOTE_TEXT_SIZE_FRACTION = 0.058f
    private const val TITLE_TEXT_SIZE_FRACTION = 0.038f
    private const val AUTHOR_TEXT_SIZE_FRACTION = 0.032f
    private const val PROGRESS_TEXT_SIZE_FRACTION = 0.03f
    private const val QUOTE_CENTER_Y_FRACTION = 0.48f
    private const val MAX_QUOTE_HEIGHT_FRACTION = 0.4f
    private const val MIN_CONTENT_TOP_FRACTION = 0.12f
    private const val QUOTE_TITLE_GAP_FRACTION = 0.025f
    private const val AUTHOR_GAP_FRACTION = 0.01f
    private const val ACCENT_Y_FRACTION = 0.83f
    private const val MAX_ACCENT_Y_FRACTION = 0.88f
    private const val ACCENT_LEFT_FRACTION = 0.42f
    private const val ACCENT_STROKE_FRACTION = 0.004f
    private const val PROGRESS_BASELINE_FRACTION = 0.93f
    private const val QUOTE_LINE_SPACING_MULTIPLIER = 1.18f
    private const val QUOTE_SHRINK_FACTOR = 0.92f
    private const val MIN_QUOTE_TEXT_SCALE = 0.62f
    private const val MAX_QUOTE_LINES = 12
}

package com.example.vibereader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.example.vibereader.domain.QuoteQualityValidator
import com.example.vibereader.domain.model.WallpaperLayoutVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object TypographyWallpaperGenerator {

    /**
     * Fully resolved drawing palette for one wallpaper page. Colors are picked
     * per layout variant and then derived for the requested dark/light theme.
     */
    private data class VariantStyle(
        val bgColors: IntArray,
        val bgStops: FloatArray,
        val quoteColor: Int,
        val accentColor: Int,
        val titleColor: Int,
        val authorColor: Int,
        val progressColor: Int,
        val quoteAlign: Paint.Align,
        val accentStyle: AccentStyle,
    )

    private enum class AccentStyle { NONE, STANDARD, BOLD, SOFT, DASHED }

    suspend fun generateWallpaperBitmap(
        context: Context,
        quote: String,
        bookTitle: String,
        progressText: String,
        bookAuthor: String? = null,
        layoutVariant: WallpaperLayoutVariant = WallpaperLayoutVariant.CLASSIC,
        isDarkMode: Boolean = true,
        /**
         * Resolution multiplier for carousel previews. Applying a wallpaper
         * always uses 1f (the device's full 1080x2400-class resolution), while
         * the pager can hold many more cards by rendering lightweight previews.
         */
        outputScale: Float = 1f,
    ): Bitmap = withContext(Dispatchers.IO) {
        if (!QuoteQualityValidator.isValidQuote(quote)) {
            throw IOException("The selected quote is incomplete or too short for a wallpaper.")
        }

        // Final safety net: even an existing library row that was imported
        // before the sanitizer existed must never paint a raw filename.
        val safeBookTitle = FilenameSanitizer.cleanBookTitle(bookTitle)
            .ifBlank { "VibeReader" }
        val safeBookAuthor = bookAuthor
            ?.let(FilenameSanitizer::cleanBookTitle)
            ?.takeIf(String::isNotBlank)

        // Always render the canonical 1080x2400 lock-screen canvas. Deriving
        // the size from display metrics made output device-dependent and could
        // produce different crops/hashes for the same quote. Previews simply
        // scale this same canvas down, so a rendered card always matches the
        // wallpaper that Apply writes.
        val scale = outputScale
            .takeIf { it.isFinite() }
            ?.coerceIn(PREVIEW_MIN_SCALE, 1f)
            ?: 1f
        val width = (WALLPAPER_WIDTH_PX * scale).roundToInt().coerceAtLeast(1)
        val height = (WALLPAPER_HEIGHT_PX * scale).roundToInt().coerceAtLeast(1)
        val widthF = width.toFloat()
        val heightF = height.toFloat()
        val bitmapBytes = width.toLong() * height * BYTES_PER_PIXEL
        if (bitmapBytes > Runtime.getRuntime().maxMemory() / MAX_HEAP_FRACTION) {
            throw IOException("The device display is too large to render safely.")
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            // Resolve palette strictly from the requested canvas theme so the
            // generated bitmap always matches the Dark/Light toggle.
            val style = styleFor(layoutVariant, isDarkMode)

            // --- Background gradient (theme-bound) --------------------------
            canvas.drawRect(
                0f, 0f, widthF, heightF,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, widthF, heightF,
                        style.bgColors, style.bgStops,
                        Shader.TileMode.CLAMP,
                    )
                },
            )

            // --- Strict layout bounds -------------------------------------
            // Horizontal: text may never live outside [margin, width - margin].
            // Vertical: all content lives inside [safeTop, safeBottom]; the
            // quote/title/author block is centered inside that band and the
            // font shrinks until the whole group fits, so nothing can ever be
            // clipped on either side of the canvas or on the lock screen.
            val horizontalMargin = widthF * HORIZONTAL_MARGIN_FRACTION
            val textWidth = (widthF - horizontalMargin * 2f).toInt().coerceAtLeast(1)
            val safeTop = heightF * CONTENT_TOP_FRACTION
            val safeBottom = heightF * CONTENT_BOTTOM_FRACTION
            val bandHeight = safeBottom - safeTop

            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.titleColor
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = widthF * TITLE_TEXT_SIZE_FRACTION
            }
            val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.authorColor
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textSize = widthF * AUTHOR_TEXT_SIZE_FRACTION
            }
            val title = ellipsize(safeBookTitle, titlePaint, textWidth)
            val author = safeBookAuthor?.let { ellipsize(it, authorPaint, textWidth) }

            val quoteGap = heightF * QUOTE_TITLE_GAP_FRACTION
            val titleHeight = titlePaint.fontMetrics.run { descent - ascent }
            val authorHeight = if (author == null) 0f else {
                authorPaint.fontMetrics.run { descent - ascent }
            }
            val titleAuthorGap = if (author == null) 0f else heightF * AUTHOR_GAP_FRACTION
            // Everything except the quote itself (gaps + single-line texts).
            val fixedHeight = quoteGap + titleHeight + titleAuthorGap + authorHeight

            val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.quoteColor
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
                textSize = widthF * QUOTE_TEXT_SIZE_FRACTION * variantTextScale(layoutVariant)
            }
            val minQuoteTextSize = quotePaint.textSize * MIN_QUOTE_TEXT_SCALE
            var quoteLayout = buildQuoteLayout(
                quote = quote.trim(),
                paint = quotePaint,
                width = textWidth,
                alignment = layoutAlignmentOf(style.quoteAlign),
            )
            // Shrink the quote until it fits inside the safe band together
            // with the title/author block — guarantees no vertical clipping.
            while (quoteLayout.height + fixedHeight > bandHeight &&
                quotePaint.textSize > minQuoteTextSize
            ) {
                quotePaint.textSize = (quotePaint.textSize * QUOTE_SHRINK_FACTOR)
                    .coerceAtLeast(minQuoteTextSize)
                quoteLayout = buildQuoteLayout(
                    quote = quote.trim(),
                    paint = quotePaint,
                    width = textWidth,
                    alignment = layoutAlignmentOf(style.quoteAlign),
                )
            }

            // Perfect vertical centering inside the safe band.
            val contentTop = safeTop + (bandHeight - quoteLayout.height - fixedHeight) / 2f
                .coerceIn(0f, bandHeight)
            val contentX = when (style.quoteAlign) {
                Paint.Align.LEFT -> horizontalMargin
                Paint.Align.RIGHT -> widthF - horizontalMargin - quoteLayout.width
                else -> (widthF - quoteLayout.width) / 2f
            }

            // --- Quote ------------------------------------------------------
            canvas.save()
            canvas.translate(contentX, contentTop)
            quoteLayout.draw(canvas)
            canvas.restore()

            // --- Title + author (always left/centered inside the margins) ---
            var y = contentTop + quoteLayout.height + quoteGap
            canvas.drawText(
                title,
                alignedX(title, titlePaint, widthF, horizontalMargin),
                y - titlePaint.fontMetrics.ascent,
                titlePaint,
            )
            if (author != null) {
                y += titleHeight + titleAuthorGap
                canvas.drawText(
                    author,
                    alignedX(author, authorPaint, widthF, horizontalMargin),
                    y - authorPaint.fontMetrics.ascent,
                    authorPaint,
                )
            }

            // --- Decorative accent line (sits below the safe band) ----------
            drawAccentLine(canvas, widthF, heightF, style.accentStyle, style.accentColor)

            // --- Progress text (bottom strip, also safe inside the margins) -
            val progressPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.progressColor
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textSize = widthF * PROGRESS_TEXT_SIZE_FRACTION
            }
            val safeProgressText = ellipsize(progressText, progressPaint, textWidth)
            val progressBaseline = heightF * PROGRESS_BASELINE_FRACTION
            canvas.drawText(
                safeProgressText,
                alignedX(safeProgressText, progressPaint, widthF, horizontalMargin),
                progressBaseline,
                progressPaint,
            )
            bitmap
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
    }

    /** x coordinate that keeps [text] inside the horizontal safe margins. */
    private fun alignedX(
        text: String,
        paint: TextPaint,
        width: Float,
        margin: Float,
    ): Float = ((width - paint.measureText(text)) / 2f)
        .coerceIn(margin, (width - margin - paint.measureText(text)).coerceAtLeast(margin))

    private fun layoutAlignmentOf(align: Paint.Align): Layout.Alignment = when (align) {
        Paint.Align.LEFT -> Layout.Alignment.ALIGN_NORMAL
        Paint.Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_CENTER
    }

    /**
     * Resolves the wallpaper palette for the selected layout variant and canvas
     * theme. Light mode uses a light canvas (#F8F9FA/#FFFFFF) with dark quote
     * text (#111827). Dark mode uses a dark canvas (#0F172A/#121212) with white
     * quote text (#FFFFFF). No hardcoded dark defaults leak into light rendering.
     */
    private fun styleFor(layoutVariant: WallpaperLayoutVariant, isDark: Boolean): VariantStyle {
        val align = when (layoutVariant) {
            WallpaperLayoutVariant.SPLIT -> Paint.Align.LEFT
            else -> Paint.Align.CENTER
        }
        val accentStyle = when (layoutVariant) {
            WallpaperLayoutVariant.MINIMAL -> AccentStyle.NONE
            WallpaperLayoutVariant.EMPHASIS -> AccentStyle.BOLD
            WallpaperLayoutVariant.OVERLAY -> AccentStyle.SOFT
            WallpaperLayoutVariant.SPLIT -> AccentStyle.DASHED
            else -> AccentStyle.STANDARD
        }
        return if (isDark) {
            // Dark Mode: dark canvas (#0F172A / #121212), white quote text.
            VariantStyle(
                bgColors = DARK_BG_COLORS,
                bgStops = DARK_BG_STOPS,
                quoteColor = DARK_QUOTE_COLOR,
                accentColor = 0xFF38BDF8.toInt(),
                titleColor = DARK_META_COLOR,
                authorColor = DARK_META_COLOR,
                progressColor = DARK_PROGRESS_COLOR,
                quoteAlign = align,
                accentStyle = accentStyle,
            )
        } else {
            // Light Mode: light canvas (#F8F9FA / #FFFFFF), dark quote text.
            VariantStyle(
                bgColors = LIGHT_BG_COLORS,
                bgStops = LIGHT_BG_STOPS,
                quoteColor = LIGHT_QUOTE_COLOR,
                accentColor = 0xFF2563EB.toInt(),
                titleColor = LIGHT_META_COLOR,
                authorColor = LIGHT_META_COLOR,
                progressColor = LIGHT_PROGRESS_COLOR,
                quoteAlign = align,
                accentStyle = accentStyle,
            )
        }
    }

    private fun drawAccentLine(
        canvas: Canvas,
        width: Float,
        height: Float,
        style: AccentStyle,
        color: Int,
    ) {
        when (style) {
            AccentStyle.NONE -> Unit
            AccentStyle.STANDARD -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
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
                    paint,
                )
            }

            AccentStyle.BOLD -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    strokeWidth = (width * ACCENT_STROKE_FRACTION * 2.5f).coerceAtLeast(4f)
                    strokeCap = Paint.Cap.ROUND
                }
                val accentY = (height * 0.86f)
                    .coerceIn(height * MIN_CONTENT_TOP_FRACTION, height * 0.92f)
                canvas.drawLine(
                    width * 0.3f,
                    accentY,
                    width * 0.7f,
                    accentY,
                    paint,
                )
            }

            AccentStyle.SOFT -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    alpha = 140
                    strokeWidth = (width * ACCENT_STROKE_FRACTION * 1.5f).coerceAtLeast(2f)
                    strokeCap = Paint.Cap.ROUND
                }
                val accentY = (height * ACCENT_Y_FRACTION)
                    .coerceIn(height * MIN_CONTENT_TOP_FRACTION, height * MAX_ACCENT_Y_FRACTION)
                canvas.drawLine(
                    width * 0.35f,
                    accentY,
                    width * 0.65f,
                    accentY,
                    paint,
                )
            }

            AccentStyle.DASHED -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    strokeWidth = (width * ACCENT_STROKE_FRACTION).coerceAtLeast(2f)
                    strokeCap = Paint.Cap.ROUND
                    pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
                }
                val accentY = (height * LIGHT_ACCENT_Y_FRACTION)
                    .coerceIn(height * MIN_CONTENT_TOP_FRACTION, height * MAX_ACCENT_Y_FRACTION)
                canvas.drawLine(
                    width * 0.25f,
                    accentY,
                    width * 0.75f,
                    accentY,
                    paint,
                )
            }
        }
    }

    private fun variantTextScale(variant: WallpaperLayoutVariant): Float = when (variant) {
        WallpaperLayoutVariant.EMPHASIS -> 1.15f
        WallpaperLayoutVariant.MINIMAL -> 0.85f
        else -> 1f
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

    private fun buildQuoteLayout(
        quote: String,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment,
    ): StaticLayout =
        StaticLayout.Builder.obtain(quote, 0, quote.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(true)
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
    private const val WALLPAPER_WIDTH_PX = 1080
    private const val WALLPAPER_HEIGHT_PX = 2400
    private const val PREVIEW_MIN_SCALE = 0.25f

    // Dark canvas theme: dark background (#0F172A / #121212), white quote text.
    private const val DARK_QUOTE_COLOR = 0xFFFFFFFF.toInt()
    private const val DARK_META_COLOR = 0xFFCBD5E1.toInt()
    private const val DARK_PROGRESS_COLOR = 0xFF94A3B8.toInt()
    private val DARK_BG_COLORS = intArrayOf(
        0xFF121212.toInt(),
        0xFF0F172A.toInt(),
        0xFF0B1220.toInt(),
    )
    private val DARK_BG_STOPS = floatArrayOf(0f, 0.55f, 1f)

    // Light canvas theme: light background (#F8F9FA / #FFFFFF), dark quote text.
    private const val LIGHT_QUOTE_COLOR = 0xFF111827.toInt()
    private const val LIGHT_META_COLOR = 0xFF4B5563.toInt()
    private const val LIGHT_PROGRESS_COLOR = 0xFF6B7280.toInt()
    private val LIGHT_BG_COLORS = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFFF8F9FA.toInt(),
        0xFFFFFFFF.toInt(),
    )
    private val LIGHT_BG_STOPS = floatArrayOf(0f, 0.52f, 1f)

    /** Safe horizontal margins — text is never drawn outside this band. */
    private const val HORIZONTAL_MARGIN_FRACTION = 0.12f

    /**
     * Safe vertical band for the quote/title/author block. The band stays clear
     * of the accent line (≈0.80–0.88) and the progress strip (≈0.93) so the
     * centered content can never collide with or clip into other elements.
     */
    private const val CONTENT_TOP_FRACTION = 0.14f
    private const val CONTENT_BOTTOM_FRACTION = 0.78f
    private const val QUOTE_TEXT_SIZE_FRACTION = 0.058f
    private const val TITLE_TEXT_SIZE_FRACTION = 0.038f
    private const val AUTHOR_TEXT_SIZE_FRACTION = 0.032f
    private const val PROGRESS_TEXT_SIZE_FRACTION = 0.03f
    private const val MIN_CONTENT_TOP_FRACTION = 0.12f
    private const val QUOTE_TITLE_GAP_FRACTION = 0.025f
    private const val AUTHOR_GAP_FRACTION = 0.01f
    private const val ACCENT_Y_FRACTION = 0.83f
    private const val MAX_ACCENT_Y_FRACTION = 0.88f
    private const val LIGHT_ACCENT_Y_FRACTION = 0.8f
    private const val ACCENT_LEFT_FRACTION = 0.42f
    private const val ACCENT_STROKE_FRACTION = 0.004f
    private const val PROGRESS_BASELINE_FRACTION = 0.93f
    private const val QUOTE_LINE_SPACING_MULTIPLIER = 1.18f
    private const val QUOTE_SHRINK_FACTOR = 0.92f
    private const val MIN_QUOTE_TEXT_SCALE = 0.62f
    /**
     * Generous relative to the 260-character quote cap (roughly 6–9 wrapped
     * lines at the minimum readable text size), so a valid quote is never
     * silently ellipsized. The shrink loop still guarantees it fits the band.
     */
    private const val MAX_QUOTE_LINES = 16

    /**
     * Hardcoded fallback quote used when the AI path cannot produce anything
     * (offline, no saved quotes/highlights, AI failure). Lets the canvas engine
     * be exercised end-to-end without depending on the network.
     */
    const val FALLBACK_QUOTE: String = "The purpose of life, it seems, is to live it."
}

package com.example.vibereader.data.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.vibereader.domain.model.EBookChapter
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.model.EBookMetadata
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PDF engine.
 *
 * Metadata comes from the PDF info dictionary through PdfBox-Android, cover art
 * from the first rendered page through the native [PdfRenderer], and page text
 * is extracted lazily so library indexing never walks the whole document.
 *
 * [PdfRenderer] is not thread-safe. All native page access in this engine is
 * serialized through [PDF_RENDER_DISPATCHER] (single-threaded IO parallelism) to
 * prevent concurrent thread locking/freezes on scanned/vector PDFs. Bitmap
 * allocation is dynamically scaled and wrapped in try-catch so OOM/memory
 * pressure falls back safely instead of hanging; every opened page is closed in
 * a `finally` block.
 */
class PdfDocumentEngine(context: Context) : EBookParserEngine {
    private val appContext = context.applicationContext

    override val format: EBookFormat = EBookFormat.PDF

    override fun readMetadata(file: File, fallbackTitle: String): EBookMetadata {
        val fallback = EBookMetadata(title = fallbackTitle.withoutExtension())
        return try {
            PDFBoxResourceLoader.init(appContext)
            PDDocument.load(file).use { document ->
                val info = document.documentInformation
                EBookMetadata(
                    title = info.title?.takeIf(String::isNotBlank) ?: fallback.title,
                    author = info.author?.takeIf(String::isNotBlank),
                    pageCount = document.numberOfPages,
                )
            }
        } catch (error: Exception) {
            // A damaged PDF should still be usable in the library under its
            // filename instead of blocking the indexing queue.
            Log.w(TAG, "Couldn't read PDF metadata from ${file.name}.", error)
            fallback
        }
    }

    override fun readChapters(file: File): List<EBookChapter> = emptyList()

    override fun readChapterText(file: File, chapterIndex: Int): String {
        if (chapterIndex < 0) return ""
        PDFBoxResourceLoader.init(appContext)
        PDDocument.load(file).use { document ->
            if (chapterIndex >= document.numberOfPages) return ""
            val stripper = PDFTextStripper().apply {
                startPage = chapterIndex + 1
                endPage = chapterIndex + 1
            }
            return stripper.getText(document).trim()
        }
    }

    override fun extractCover(file: File, destination: File): Boolean =
        try {
            val bitmap = renderPageBitmapSerialized(
                file = file,
                pageIndex = 0,
                targetWidth = COVER_WIDTH,
                targetHeight = COVER_HEIGHT,
            ) ?: throw IOException("Could not render a safe PDF cover preview.")
            try {
                destination.outputStream().buffered().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("Could not encode the PDF cover.")
                    }
                }
            } finally {
                bitmap.recycle()
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "Couldn't extract a cover for ${file.name}.", error)
            false
        }

    private fun String.withoutExtension(): String = substringBeforeLast('.', this)

    private companion object {
        const val TAG = "PdfDocumentEngine"
        const val COVER_WIDTH = 300
        const val COVER_HEIGHT = 400
        private const val MAX_BITMAP_PIXELS = 8_000_000L
        private const val MAX_BITMAP_DIMENSION = 2048
        private const val PREVIEW_MAX_DIMENSION = 720

        /**
         * Single-thread serializer for Android [PdfRenderer] access. Fast or
         * concurrent page operations on the same renderer can lock/crash the
         * native process; all `openPage`/`render`/`close` work must run here.
         */
        val PDF_RENDER_DISPATCHER: CoroutineDispatcher =
            Dispatchers.IO.limitedParallelism(1)

        /**
         * Opens the PDF, renders one page, and always closes the page/renderer/
         * descriptor. Safe for scanned/vector PDFs: bitmap allocation is scaled
         * dynamically and wrapped in try-catch; OOM falls back to a readable
         * low-resolution preview instead of an infinite loading/failure loop.
         */
        fun renderPageBitmapSerialized(
            file: File,
            pageIndex: Int,
            targetWidth: Int,
            targetHeight: Int = 0,
        ): Bitmap? = runBlocking {
            withContext(PDF_RENDER_DISPATCHER) {
                renderPageBitmapInternal(file, pageIndex, targetWidth, targetHeight)
            }
        }

        private fun renderPageBitmapInternal(
            file: File,
            pageIndex: Int,
            targetWidth: Int,
            targetHeight: Int,
        ): Bitmap? {
            if (!file.isFile || pageIndex < 0 || targetWidth <= 0) return null
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            var page: PdfRenderer.Page? = null
            return try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(descriptor)
                if (pageIndex >= renderer.pageCount) return null
                page = renderer.openPage(pageIndex)
                renderPageSafely(page, targetWidth, targetHeight)
            } catch (error: Throwable) {
                Log.w(TAG, "Safe PDF render failed for ${file.name} page=$pageIndex.", error)
                null
            } finally {
                try {
                    page?.close()
                } catch (closeError: Throwable) {
                    Log.w(TAG, "Failed to close PDF page $pageIndex.", closeError)
                }
                try {
                    renderer?.close()
                } catch (closeError: Throwable) {
                    Log.w(TAG, "Failed to close PdfRenderer for ${file.name}.", closeError)
                }
                try {
                    descriptor?.close()
                } catch (closeError: Throwable) {
                    Log.w(TAG, "Failed to close PDF descriptor for ${file.name}.", closeError)
                }
            }
        }

        private fun renderPageSafely(
            page: PdfRenderer.Page,
            targetWidth: Int,
            targetHeight: Int,
        ): Bitmap? {
            val pageWidth = page.width
            val pageHeight = page.height
            if (pageWidth <= 0 || pageHeight <= 0) return null

            val fitScale = if (targetHeight > 0) {
                minOf(
                    targetWidth.toFloat() / pageWidth,
                    targetHeight.toFloat() / pageHeight,
                )
            } else {
                targetWidth.toFloat() / pageWidth
            }
            if (!fitScale.isFinite() || fitScale <= 0f) return null

            val pagePixels = pageWidth.toDouble() * pageHeight.toDouble()
            val maxScaleForPixels = if (pagePixels > 0.0) {
                sqrt(MAX_BITMAP_PIXELS.toDouble() / pagePixels).toFloat()
            } else {
                0f
            }
            val maxScaleForDimension =
                MAX_BITMAP_DIMENSION.toFloat() / maxOf(pageWidth, pageHeight).coerceAtLeast(1)
            val scale = minOf(fitScale, maxScaleForPixels, maxScaleForDimension)
            if (!scale.isFinite() || scale <= 0f) return null

            val width = (pageWidth * scale).roundToInt().coerceIn(1, MAX_BITMAP_DIMENSION)
            val height = (pageHeight * scale).roundToInt().coerceIn(1, MAX_BITMAP_DIMENSION)
            val pixels = width.toLong() * height.toLong()
            if (pixels <= 0L || pixels > MAX_BITMAP_PIXELS) return null

            val bitmapRef = AtomicReference<Bitmap?>()
            return try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmapRef.set(bitmap)
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply { setScale(scale, scale) }
                page.render(
                    bitmap,
                    Rect(0, 0, width, height),
                    matrix,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                )
                bitmap
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "PDF bitmap OOM (${width}x$height); using preview fallback.", oom)
                runCatching { bitmapRef.getAndSet(null)?.recycle() }
                renderPreviewFallback(page)
            } catch (error: Throwable) {
                Log.w(TAG, "PDF page render failed; using safe fallback.", error)
                runCatching { bitmapRef.getAndSet(null)?.recycle() }
                null
            }
        }

        /** Low-resolution fallback so a heavy/scanned page remains readable. */
        private fun renderPreviewFallback(page: PdfRenderer.Page): Bitmap? {
            val longest = maxOf(page.width, page.height).coerceAtLeast(1)
            val scale = (PREVIEW_MAX_DIMENSION.toFloat() / longest.toFloat()).coerceIn(0.05f, 1f)
            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
            var bitmap: Bitmap? = null
            return try {
                val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap = created
                created.eraseColor(Color.WHITE)
                val matrix = Matrix().apply { setScale(scale, scale) }
                page.render(
                    created,
                    Rect(0, 0, width, height),
                    matrix,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                )
                created
            } catch (_: Throwable) {
                runCatching { bitmap?.recycle() }
                null
            }
        }
    }
}

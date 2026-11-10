package com.example.vibereader.data.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.example.vibereader.domain.model.EBookChapter
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.model.EBookMetadata
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 *
 * Page rendering uses an industry-standard previous/current/next window backed by
 * an [LruCache] of [CACHE_PAGE_CAPACITY] page bitmaps. A requested page returns
 * immediately on a cache hit; a miss renders on [PDF_RENDER_DISPATCHER] and then
 * silently prefetches the adjacent pages on a non-blocking coroutine. The cache
 * is scoped to the file identity (path + size + mtime) and is cleared when a
 * different PDF is opened, so pages can never leak between books.
 */
class PdfDocumentEngine(context: Context) : EBookParserEngine {
    private val appContext = context.applicationContext

    /**
     * The single dispatcher shared by every native [PdfRenderer] operation in
     * this engine (requested renders, prefetches, and cover extraction).
     */
    private val pdfDispatcher: CoroutineDispatcher = PDF_RENDER_DISPATCHER

    /** Fire-and-forget scope for silent adjacent-page prefetching. */
    private val prefetchScope = CoroutineScope(SupervisorJob() + pdfDispatcher)

    /** Previous/current/next window with headroom for fast page flips. */
    private val pageCache = object : LruCache<Int, Bitmap>(CACHE_PAGE_CAPACITY) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }

    private val cacheLock = Any()
    private var cacheIdentity: String? = null
    private var pageCountIdentity: String? = null
    private var pageCountValue: Int = 0

    @Volatile
    private var prefetchJob: Job? = null

    /**
     * Increments whenever a page bitmap is added to [pageCache]. Compose can
     * collect this to re-read the cache synchronously during composition when a
     * silent prefetch finishes after the page item was first composed.
     */
    private val _cacheVersion = MutableStateFlow(0)
    val cacheVersion: StateFlow<Int> = _cacheVersion.asStateFlow()

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

    /**
     * Synchronous, composition-safe cache read. Returns the already-rendered
     * bitmap for [pageIndex] without dispatching or touching the native renderer.
     */
    fun getCachedPage(pageIndex: Int): Bitmap? = pageCache.get(pageIndex)

    /**
     * Schedules the silent previous/next window without re-rendering the current
     * page. Called from the reader as soon as a page is requested so adjacent
     * pages are ready before the user swipes to them.
     */
    fun prefetchAround(
        file: File,
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int = 0,
    ) {
        if (!file.isFile || pageIndex < 0 || targetWidth <= 0) return
        val identity = fileIdentity(file)
        synchronized(cacheLock) {
            if (cacheIdentity != identity) {
                pageCache.evictAll()
                cacheIdentity = identity
            }
        }
        schedulePrefetch(file, identity, pageIndex, targetWidth, targetHeight)
    }

    /**
     * Returns the bitmap for [pageIndex] using the standard three-page window.
     *
     * - A cache hit returns immediately without touching the native renderer.
     * - A miss renders on [pdfDispatcher], stores the result, and returns it.
     * - Immediately afterwards, adjacent pages (next and previous) are silently
     *   prefetched by a non-blocking coroutine on the same dispatcher.
     *
     * The return value is never recycled by the cache; an evicted bitmap may
     * still be safely displayed by the UI until it is garbage collected.
     */
    suspend fun renderPage(
        file: File,
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int = 0,
    ): Bitmap? {
        if (!file.isFile || pageIndex < 0 || targetWidth <= 0) return null
        val identity = fileIdentity(file)

        val cached = cacheLookup(identity, pageIndex)
        if (cached != null) {
            schedulePrefetch(file, identity, pageIndex, targetWidth, targetHeight)
            return cached
        }

        val rendered = withContext(pdfDispatcher) {
            // Re-check inside the serialized dispatcher: a prefetch or another
            // caller may have completed while this request was queued.
            cacheLookup(identity, pageIndex) ?: run {
                val bitmap = renderPageBitmapInternal(
                    file = file,
                    pageIndex = pageIndex,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                )
                if (bitmap != null) {
                    cacheStore(identity, pageIndex, bitmap)
                }
                bitmap
            }
        }

        if (rendered != null) {
            schedulePrefetch(file, identity, pageIndex, targetWidth, targetHeight)
        }
        return rendered
    }

    /** Reads/caches the page count for [file]; safe to call from any dispatcher. */
    suspend fun getPageCount(file: File): Int = withContext(pdfDispatcher) {
        if (!file.isFile) return@withContext 0
        pageCountOnDispatcher(file, fileIdentity(file))
    }

    /** Drops all memory-cached pages and cancels any in-flight prefetch. */
    fun clearPageCache() {
        prefetchJob?.cancel()
        prefetchJob = null
        synchronized(cacheLock) {
            pageCache.evictAll()
            cacheIdentity = null
            pageCountIdentity = null
            pageCountValue = 0
        }
        _cacheVersion.update { it + 1 }
    }

    private fun fileIdentity(file: File): String =
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    /**
     * A hit returns the bitmap directly. A different file identity evicts the
     * previous book's window before the lookup so cross-book pages never leak.
     */
    private fun cacheLookup(identity: String, pageIndex: Int): Bitmap? = synchronized(cacheLock) {
        if (cacheIdentity != identity) {
            pageCache.evictAll()
            cacheIdentity = identity
        }
        pageCache.get(pageIndex)
    }

    private fun cacheStore(identity: String, pageIndex: Int, bitmap: Bitmap) {
        val stored = synchronized(cacheLock) {
            if (cacheIdentity != identity) return
            pageCache.put(pageIndex, bitmap)
            true
        }
        if (stored) {
            _cacheVersion.update { it + 1 }
        }
    }

    /**
     * Cancels the previous window's prefetch and launches a new non-blocking
     * one for [pageIndex] +/- 1. Both jobs run on [pdfDispatcher], so native
     * open/render/close calls remain strictly serialized with foreground renders.
     */
    private fun schedulePrefetch(
        file: File,
        identity: String,
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
    ) {
        prefetchJob?.cancel()
        prefetchJob = prefetchScope.launch {
            try {
                val totalPages = pageCountOnDispatcher(file, identity)
                if (totalPages <= 0) return@launch
                val neighbours = listOf(pageIndex + 1, pageIndex - 1)
                for (neighbour in neighbours) {
                    if (!isActive) return@launch
                    if (neighbour !in 0 until totalPages) continue
                    if (cacheLookup(identity, neighbour) != null) continue
                    val bitmap = renderPageBitmapInternal(
                        file = file,
                        pageIndex = neighbour,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                    ) ?: continue
                    cacheStore(identity, neighbour, bitmap)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Silent: prefetch must never surface UI errors or interrupt reading.
                Log.w(TAG, "Silent PDF prefetch failed for ${file.name} page=$pageIndex.", error)
            }
        }
    }

    /** Native page-count probe; only ever called on [pdfDispatcher]. */
    private fun pageCountOnDispatcher(file: File, identity: String): Int {
        synchronized(cacheLock) {
            if (pageCountIdentity == identity) return pageCountValue
        }
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            val count = renderer.pageCount
            synchronized(cacheLock) {
                pageCountIdentity = identity
                pageCountValue = count
            }
            count
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read PDF page count for ${file.name}.", error)
            0
        } finally {
            try {
                renderer?.close()
            } catch (closeError: Throwable) {
                Log.w(TAG, "Failed to close page-count PdfRenderer for ${file.name}.", closeError)
            }
            try {
                descriptor?.close()
            } catch (closeError: Throwable) {
                Log.w(TAG, "Failed to close page-count descriptor for ${file.name}.", closeError)
            }
        }
    }

    private fun String.withoutExtension(): String = substringBeforeLast('.', this)

    private companion object {
        const val TAG = "PdfDocumentEngine"
        const val COVER_WIDTH = 300
        const val COVER_HEIGHT = 400

        /**
         * 10 page bitmaps = a full previous/current/next window at both ends of
         * a fast flip sequence, with headroom for Compose's off-screen pager
         * prefetch. Bitmaps are never recycled while the cache drops them, so a
         * page already being displayed remains valid until garbage collected.
         */
        const val CACHE_PAGE_CAPACITY = 10

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

package com.example.vibereader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

object PdfPageCache {
    private const val DISK_CACHE_LIMIT_BYTES = 128L * 1024 * 1024
    private const val CACHE_DIRECTORY = "pdf_pages"
    private val diskLock = Any()
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val memoryCache = object : LruCache<String, Bitmap>(maxMemoryKb / 4) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int =
            (bitmap.byteCount / 1024).coerceAtLeast(1)
    }

    fun get(context: Context, bookKey: String, pageIndex: Int, requestedWidthPx: Int): Bitmap? {
        val memoryKeyPrefix = "$bookKey:$pageIndex:"
        val memoryMatch = memoryCache.snapshot()
            .filter { (key, bitmap) ->
                key.startsWith(memoryKeyPrefix) && bitmap.width >= requestedWidthPx
            }
            .minByOrNull { (_, bitmap) -> bitmap.width }
            ?.value
        if (memoryMatch != null) return memoryMatch

        val diskFile = cacheFile(context, bookKey, pageIndex, requestedWidthPx)
        if (!diskFile.isFile) return null
        return synchronized(diskLock) {
            try {
                BitmapFactory.decodeFile(diskFile.absolutePath)?.also { bitmap ->
                    memoryCache.put(memoryKey(bookKey, pageIndex, bitmap.width), bitmap)
                    diskFile.setLastModified(System.currentTimeMillis())
                }
                    ?: run {
                        Log.w(TAG, "Ignoring unreadable cached PDF page: ${diskFile.name}")
                        diskFile.delete()
                        null
                    }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not read cached PDF page: ${diskFile.name}", error)
                diskFile.delete()
                null
            }
        }
    }

    fun putMemory(bookKey: String, pageIndex: Int, bitmap: Bitmap) {
        memoryCache.put(memoryKey(bookKey, pageIndex, bitmap.width), bitmap)
    }

    fun putDisk(
        context: Context,
        bookKey: String,
        pageIndex: Int,
        requestedWidthPx: Int,
        bitmap: Bitmap,
    ) {
        synchronized(diskLock) {
            val directory = File(context.cacheDir, CACHE_DIRECTORY)
            val destination = cacheFile(context, bookKey, pageIndex, requestedWidthPx)
            val temporary = File(directory, "${destination.name}.tmp")
            try {
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Could not create PDF page cache directory.")
                }
                FileOutputStream(temporary).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("Could not encode PDF page for disk cache.")
                    }
                    output.fd.sync()
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Could not save PDF page to disk cache.")
                }
                trimDiskCache(directory, destination)
            } catch (error: IOException) {
                Log.w(TAG, "Could not write cached PDF page: ${destination.name}", error)
                temporary.delete()
            } catch (error: SecurityException) {
                Log.w(TAG, "Could not access PDF page cache: ${destination.name}", error)
                temporary.delete()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Could not encode PDF page for cache: ${destination.name}", error)
                temporary.delete()
            }
        }
    }

    private fun trimDiskCache(directory: File, newestFile: File) {
        val files = directory.listFiles()
            ?.filter(File::isFile)
            ?.sortedBy { it.lastModified() }
            ?: throw IOException("Could not inspect PDF page cache directory.")
        if (newestFile.length() > DISK_CACHE_LIMIT_BYTES) {
            newestFile.delete()
            return
        }
        var totalBytes = files.sumOf(File::length)
        for (file in files) {
            if (totalBytes <= DISK_CACHE_LIMIT_BYTES) break
            val fileSize = file.length()
            if (file != newestFile && file.delete()) totalBytes -= fileSize
        }
    }

    private fun cacheFile(context: Context, bookKey: String, pageIndex: Int, widthPx: Int): File {
        val directory = File(context.cacheDir, CACHE_DIRECTORY)
        return File(directory, "${digest("$bookKey:$pageIndex:$widthPx")}.png")
    }

    private fun memoryKey(bookKey: String, pageIndex: Int, widthPx: Int): String =
        "$bookKey:$pageIndex:$widthPx"

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private const val TAG = "PdfPageCache"
}

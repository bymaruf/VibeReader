package com.example.vibereader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.vibereader.data.repository.BookFormat
import nl.siegmann.epublib.epub.EpubReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException

object CoverExtractor {
    fun extractCover(context: Context, bookFile: File, format: BookFormat): String? {
        return try {
            val coversDirectory = File(context.cacheDir, COVERS_DIRECTORY)
            if (!coversDirectory.exists() && !coversDirectory.mkdirs()) {
                throw IOException("Could not create the cover cache directory.")
            }
            val coverFile = File(coversDirectory, "${bookFile.nameWithoutExtension}.png")
            when (format) {
                BookFormat.PDF -> extractPdfCover(bookFile, coverFile)
                BookFormat.EPUB -> extractEpubCover(bookFile, coverFile)
            }
            coverFile.absolutePath
        } catch (error: Exception) {
            Log.w(TAG, "Couldn't extract a cover for ${bookFile.name}.", error)
            null
        }
    }

    private fun extractPdfCover(bookFile: File, coverFile: File) {
        ParcelFileDescriptor.open(bookFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) throw IOException("The PDF has no pages.")
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(
                            bitmap,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        coverFile.outputStream().buffered().use { output ->
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                throw IOException("Could not encode the PDF cover.")
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun extractEpubCover(bookFile: File, coverFile: File) {
        val coverBytes = FileInputStream(bookFile).use { input ->
            EpubReader().readEpub(input).coverImage?.data
        } ?: throw IOException("The EPUB does not contain a cover image.")
        coverFile.outputStream().buffered().use { output ->
            output.write(coverBytes)
        }
    }

    private const val TAG = "VibeReaderCover"
    private const val COVERS_DIRECTORY = "covers"
    private const val COVER_WIDTH = 300
    private const val COVER_HEIGHT = 400
}

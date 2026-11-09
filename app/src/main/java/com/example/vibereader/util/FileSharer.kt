package com.example.vibereader.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.domain.model.EBookFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object FileSharer {
    fun shareBookFile(context: Context, book: BookEntity) {
        val sourceUri = Uri.parse(book.documentUri)
        val file = book.filePath.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)
        val uri = when {
            file != null && isAppBookFile(context, file) -> FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            sourceUri.scheme == "content" -> sourceUri
            else -> {
                showError(context, "The original book file is not available to share.")
                return
            }
        }
        val mimeType = EBookFormat.fromLabel(book.format)?.mimeType ?: run {
            showError(context, "This book format cannot be shared.")
            return
        }
        launchChooser(
            context,
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, book.displayTitle())
                clipData = android.content.ClipData.newUri(
                    context.contentResolver,
                    book.displayTitle(),
                    uri,
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share Book File",
        )
    }

    fun shareQuoteText(context: Context, quote: QuoteEntity, bookTitle: String) {
        val formatted = "\"${quote.selectedText}\"\n\n— Extracted from $bookTitle via VibeReader"
        launchChooser(
            context,
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, formatted)
                putExtra(Intent.EXTRA_SUBJECT, "Quote from $bookTitle")
            },
            "Share Quote",
        )
    }

    fun shareQuoteImage(context: Context, quote: QuoteEntity, bookTitle: String) {
        val outputDirectory = File(context.cacheDir, SHARED_QUOTES_DIRECTORY)
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            showError(context, "Couldn't prepare the quote image.")
            return
        }
        val imageFile = File(outputDirectory, "quote-${quote.id}.png")
        try {
            QuoteImageRenderer.write(imageFile, quote.selectedText, bookTitle)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile,
            )
            launchChooser(
                context,
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Quote from $bookTitle")
                    clipData = android.content.ClipData.newUri(context.contentResolver, "VibeReader quote", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share Quote Image",
            )
        } catch (error: Exception) {
            showError(context, error.localizedMessage ?: "Couldn't create the quote image.")
        }
    }

    private fun isAppBookFile(context: Context, file: File): Boolean {
        val booksDirectory = File(context.filesDir, BOOKS_DIRECTORY).canonicalFile
        val canonicalFile = file.canonicalFile
        return canonicalFile.path.startsWith(booksDirectory.path + File.separator)
    }

    private fun launchChooser(context: Context, intent: Intent, title: String) {
        val chooser = Intent.createChooser(intent, title)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (error: android.content.ActivityNotFoundException) {
            showError(context, "No app is available to share this item.")
        }
    }

    private fun showError(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private const val BOOKS_DIRECTORY = "books"
    private const val SHARED_QUOTES_DIRECTORY = "shared_quotes"
}

private object QuoteImageRenderer {
    fun write(file: File, quote: String, bookTitle: String) {
        val width = 1080
        val height = 1350
        val bitmap = android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.BLACK)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 58f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            val maxWidth = width - IMAGE_HORIZONTAL_PADDING * 2
            var y = 410f
            wrapText(quote, paint, maxWidth).forEach { line ->
                canvas.drawText(line, IMAGE_HORIZONTAL_PADDING.toFloat(), y, paint)
                y += paint.textSize * 1.45f
            }
            paint.textSize = 34f
            paint.alpha = 170
            canvas.drawText("— $bookTitle", IMAGE_HORIZONTAL_PADDING.toFloat(), height - 130f, paint)
            FileOutputStream(file).use { output ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("Couldn't encode quote image.")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun wrapText(text: String, paint: android.graphics.Paint, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        text.split(Regex("\\s+")).forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private const val IMAGE_HORIZONTAL_PADDING = 100
}

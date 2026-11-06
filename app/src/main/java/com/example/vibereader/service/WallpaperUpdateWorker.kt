package com.example.vibereader.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.remote.QuoteSelectionSource
import com.example.vibereader.data.remote.WallpaperQuoteCurator
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.HighlightEntity
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.util.WallpaperApplyManager
import com.example.vibereader.util.TypographyWallpaperGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

class WallpaperUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = WallpaperPreferences(applicationContext)
        val settings = preferences.getSettings()
        val isManualRequest = inputData.getBoolean(KEY_MANUAL_REQUEST, false)
        if (!settings.isWallpaperEnabled && !isManualRequest) return@withContext Result.success()

        try {
            val wallpaperContent = loadWallpaperContent()
            if (wallpaperContent.quoteSource != QuoteSelectionSource.AI) {
                Log.w(
                    TAG,
                    "Using ${wallpaperContent.quoteSource} wallpaper quote: " +
                        (wallpaperContent.fallbackReason ?: "no configured AI provider"),
                )
            }
            preferences.savePromptContext(
                "Book: ${wallpaperContent.book.title}\n${wallpaperContent.progressText}",
            )
            val bitmap = TypographyWallpaperGenerator.generateWallpaperBitmap(
                context = applicationContext,
                quote = wallpaperContent.quote,
                bookTitle = wallpaperContent.book.title,
                bookAuthor = wallpaperContent.book.author,
                progressText = wallpaperContent.progressText,
            )
            try {
                WallpaperApplyManager.applyWallpaper(
                    applicationContext,
                    bitmap,
                    settings.applyTarget,
                ).getOrThrow()
                val wallpaperFile = TypographyWallpaperGenerator.saveWallpaperPreview(
                    applicationContext,
                    bitmap,
                )
                preferences.saveCurrentWallpaperPath(wallpaperFile.absolutePath)
            } finally {
                bitmap.recycle()
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Log.e(TAG, "Local wallpaper generation or apply failed", error)
            Result.failure(errorOutput(error))
        } catch (error: Exception) {
            Log.e(TAG, "Unexpected wallpaper update failure", error)
            Result.failure(errorOutput(error))
        }
    }

    private suspend fun loadWallpaperContent(): WallpaperContent {
        val database = VibeReaderDatabase.getInstance(applicationContext)
        val books = database.bookDao().getAll()
        val savedQuotes = database.quoteDao().observeAll().first()
        val savedHighlights = database.highlightDao().observeAll().first()
        val latestQuote = savedQuotes.firstOrNull()
        val latestHighlight = savedHighlights.firstOrNull()
        val savedText = newestContent(latestQuote, latestHighlight)
            ?: throw IOException("Save a quote or highlight while reading to create a wallpaper.")
        val book = books.firstOrNull { it.id == savedText.bookId }
            ?: throw IOException("The book for this quote or highlight is no longer available.")

        val bookQuotes = savedQuotes.filter { it.bookId == book.id }.map(QuoteEntity::text)
        val bookHighlights = savedHighlights.filter { it.bookId == book.id }.map(HighlightEntity::text)
        val chapterText = database.bookChapterDao().getForBook(book.id).map { it.text }
        val curatedQuote = WallpaperQuoteCurator(applicationContext).curate(
            bookTitle = book.title,
            bookAuthor = book.author,
            savedText = bookQuotes + bookHighlights,
            chapterText = chapterText,
        )
        val progress = database.readingProgressDao().getForBook(book.id)?.progress
            ?.coerceIn(0f, 1f) ?: 0f
        val progressPercent = (progress * 100).toInt()
        val progressText = "Vibe Reader • $progressPercent% through ${book.title}"
        return WallpaperContent(
            book = book,
            quote = curatedQuote.quote.take(MAX_QUOTE_CHARS),
            progressText = progressText,
            quoteSource = curatedQuote.source,
            fallbackReason = curatedQuote.fallbackReason,
        )
    }

    private fun newestContent(
        quote: QuoteEntity?,
        highlight: HighlightEntity?,
    ): SavedReadingText? {
        val savedQuote = quote?.let { SavedReadingText(it.bookId, it.text, it.createdAt) }
        val savedHighlight = highlight?.let {
            SavedReadingText(it.bookId, it.text, it.createdAt)
        }
        return listOfNotNull(savedQuote, savedHighlight).maxByOrNull(SavedReadingText::createdAt)
    }

    private data class WallpaperContent(
        val book: BookEntity,
        val quote: String,
        val progressText: String,
        val quoteSource: QuoteSelectionSource,
        val fallbackReason: String?,
    )

    private data class SavedReadingText(
        val bookId: String,
        val text: String,
        val createdAt: Long,
    )

    companion object {
        const val KEY_MANUAL_REQUEST = "manual_wallpaper_request"
        private const val MAX_QUOTE_CHARS = 1_200
        private const val TAG = "WallpaperUpdateWorker"
    }

    private fun errorOutput(error: Exception): Data =
        Data.Builder()
            .putString("error", error.localizedMessage ?: "Wallpaper generation failed.")
            .build()
}

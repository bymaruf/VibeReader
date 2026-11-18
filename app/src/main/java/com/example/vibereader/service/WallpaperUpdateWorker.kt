package com.example.vibereader.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.document.EBookDocumentLoader
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.domain.model.EBookDocument
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.model.EBookMetadata
import com.example.vibereader.util.FilenameSanitizer
import com.example.vibereader.util.TypographyWallpaperGenerator
import com.example.vibereader.util.WallpaperApplyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Renders and applies the next user-approved quote.
 *
 * Everything below runs offline from local Room data and the original book
 * file. The worker never throws out of [doWork]; every failure is returned as
 * [Result.failure] with a user-readable `error` value in the output Data so the
 * Quotes screen can show it.
 */
class WallpaperUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val isManualRequest = inputData.getBoolean(KEY_MANUAL_REQUEST, false)
        Log.d(TAG, "doWork started (manual=$isManualRequest, attempt=$runAttemptCount)")
        return try {
            withContext(Dispatchers.IO) {
                renderAndApplyNextApprovedQuote(isManualRequest)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                Log.d(TAG, "doWork cancelled")
                Result.failure(workDataOf(ERROR_KEY to "Wallpaper update cancelled."))
            } else {
                val message = error.message?.takeIf(String::isNotBlank)
                    ?: "Wallpaper update failed."
                Log.e(TAG, "Wallpaper update failed: $message", error)
                Result.failure(workDataOf(ERROR_KEY to message))
            }
        }
    }

    private suspend fun renderAndApplyNextApprovedQuote(isManualRequest: Boolean): Result {
        val preferences = WallpaperPreferences(applicationContext)
        val settings = preferences.getSettings()
        if (!settings.isWallpaperEnabled && !isManualRequest) {
            Log.d(TAG, "Skipping: rotation is off and this is not a manual request")
            return Result.success()
        }

        val wallpaperContent = loadWallpaperContent(preferences)
        Log.d(
            TAG,
            "Selected next approved quote id=${wallpaperContent.quote.id} " +
                "from '${wallpaperContent.document.title}' (dark=${settings.isDarkTheme})",
        )
        preferences.savePromptContext(
            "Book: ${wallpaperContent.document.title}\n${wallpaperContent.progressText}",
        )
        val bitmap = TypographyWallpaperGenerator.generateWallpaperBitmap(
            context = applicationContext,
            // Validate and render the complete approved quote. Truncating it
            // first used to remove the terminal punctuation, fail validation,
            // and leave the rotation cursor stuck on the same bad row.
            quote = wallpaperContent.quote.selectedText,
            bookTitle = wallpaperContent.document.title,
            bookAuthor = wallpaperContent.document.author,
            progressText = wallpaperContent.progressText,
            // Scheduled/manual workers must honour the same theme the Studio
            // toggle persisted, otherwise they apply dark forever.
            isDarkMode = settings.isDarkTheme,
        )
        try {
            Log.d(TAG, "Applying wallpaper to ${settings.applyTarget}")
            WallpaperApplyManager.applyWallpaper(
                applicationContext,
                bitmap,
                settings.applyTarget,
            ).getOrThrow()
            // Advance the rotation cursor only after the system accepted it.
            preferences.saveLastWallpaperQuoteId(wallpaperContent.quote.id)
            Log.d(TAG, "Wallpaper applied successfully")

            val wallpaperFile = TypographyWallpaperGenerator.saveWallpaperPreview(
                applicationContext,
                bitmap,
            )
            preferences.saveCurrentWallpaperPath(wallpaperFile.absolutePath)
            Log.d(TAG, "Saved wallpaper preview at ${wallpaperFile.absolutePath}")
        } finally {
            bitmap.recycle()
        }
        return Result.success()
    }

    /** Picks the next approved quote after the previously applied one. */
    private suspend fun loadWallpaperContent(
        preferences: WallpaperPreferences,
    ): WallpaperContent {
        val database = VibeReaderDatabase.getInstance(applicationContext)
        val repository = com.example.vibereader.data.repository.QuoteRepository(database)
        // Human-in-the-loop: only quotes the user explicitly approved may be
        // rendered. Sequential/round-robin rotation advances from the last
        // applied quote ID stored in preferences (LAST_WALLPAPER_QUOTE_ID).
        val nextQuote = repository.getNextApprovedQuote(preferences.getLastWallpaperQuoteId())
            ?: throw IOException(
                "No approved quotes. Open Quotes and tick \"Set as Wallpaper\" " +
                    "on at least one quote first.",
            )

        val book = database.bookDao().getById(nextQuote.bookId)
            ?: throw IOException(
                "The book for the next approved quote is no longer available.",
            )

        val documentLoader = EBookDocumentLoader(applicationContext)
        val bookDocument = loadBookDocument(book, documentLoader)
        val progress = database.readingProgressDao().getForBook(book.id)?.progress
            ?.coerceIn(0f, 1f) ?: 0f
        val progressPercent = (progress * 100).toInt()
        val safeTitle = FilenameSanitizer.cleanBookTitle(bookDocument.title)
            .ifBlank { "Untitled Book" }
        val progressText = "Vibe Reader • $progressPercent% through $safeTitle"
        return WallpaperContent(
            quote = nextQuote,
            document = bookDocument,
            progressText = progressText,
        )
    }

    private suspend fun loadBookDocument(
        book: BookEntity,
        loader: EBookDocumentLoader,
    ): EBookDocument {
        val format = EBookFormat.fromLabel(book.format)
            ?: EBookFormat.fromFileName(book.filePath)
        val fallbackTitle = FilenameSanitizer.cleanBookTitle(book.title)
            .ifBlank { "Untitled Book" }
        val file = book.filePath.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)
        if (format != null && file != null) {
            try {
                return loader.loadDocument(
                    bookId = book.id,
                    file = file,
                    format = format,
                    fallbackTitle = fallbackTitle,
                    includeChapters = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Couldn't read ${book.format} metadata from ${file.name}.", error)
            }
        }
        return EBookDocument(
            bookId = book.id,
            format = format ?: EBookFormat.PDF,
            filePath = book.filePath,
            metadata = EBookMetadata(title = fallbackTitle, author = book.author),
        )
    }

    private data class WallpaperContent(
        val quote: QuoteEntity,
        val document: EBookDocument,
        val progressText: String,
    )

    companion object {
        const val KEY_MANUAL_REQUEST = "manual_wallpaper_request"
        const val ERROR_KEY = "error"
        private const val TAG = "WallpaperRotation"
    }
}

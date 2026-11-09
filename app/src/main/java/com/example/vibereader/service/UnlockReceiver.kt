package com.example.vibereader.service

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.document.EBookDocumentLoader
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.local.WallpaperTarget
import com.example.vibereader.data.local.WallpaperUpdateTrigger
import com.example.vibereader.data.repository.QuoteRepository
import com.example.vibereader.domain.model.EBookDocument
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.model.EBookMetadata
import com.example.vibereader.util.FilenameSanitizer
import com.example.vibereader.util.TypographyWallpaperGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Wallpaper rotation broadcast handler.
 *
 * - [Intent.ACTION_USER_PRESENT]: when the saved update trigger is ON_UNLOCK,
 *   retrieves the NEXT approved quote in the rotation queue (sequential
 *   round-robin via [WallpaperPreferences.getLastWallpaperQuoteId]/saved cursor),
 *   renders its bitmap, and applies it via
 *   [WallpaperManager.getInstance] + `setBitmap(...)` inside a clean try-catch.
 *   Work runs via [goAsync] so the receiver never blocks the main thread or
 *   crashes the host process.
 * - [Intent.ACTION_BOOT_COMPLETED] / [Intent.ACTION_MY_PACKAGE_REPLACED]:
 *   restores the saved automatic update frequency (re-schedules periodic work
 *   or re-registers the dynamic unlock receiver) after reboot or app update.
 *
 * On Android 8+ ACTION_USER_PRESENT is not reliably delivered to manifest
 * receivers, so the app also registers this receiver dynamically while alive
 * via [register].
 */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        try {
            when (action) {
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "ACTION_USER_PRESENT received")
                    handleUnlock(appContext)
                }

                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    Log.d(TAG, "$action received; restoring wallpaper rotation state")
                    restoreWallpaperRotation(appContext)
                }
            }
        } catch (error: Exception) {
            // Receivers must never crash the host process.
            Log.e(TAG, "Wallpaper broadcast handling failed for action=$action", error)
        }
    }

    private fun handleUnlock(context: Context) {
        val settings = runCatching { WallpaperPreferences(context).getSettings() }.getOrNull()
            ?: return
        Log.d(
            TAG,
            "Unlock received: enabled=${settings.isWallpaperEnabled}, trigger=${settings.updateTrigger}",
        )
        if (!settings.isWallpaperEnabled ||
            settings.updateTrigger != WallpaperUpdateTrigger.ON_UNLOCK
        ) {
            return
        }
        val pendingResult = try {
            goAsync()
        } catch (error: Exception) {
            Log.e(TAG, "goAsync unavailable; falling back to WorkManager enqueue", error)
            WallpaperRotationManager.triggerNextWallpaperUpdate(context)
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                applyNextApprovedQuoteOnUnlock(context)
            } catch (error: Throwable) {
                Log.e(TAG, "On-unlock wallpaper rotation failed", error)
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Retrieves the NEXT approved quote bitmap in the rotation queue and applies
     * it with [WallpaperManager.setBitmap]. The cursor ([LAST_WALLPAPER_QUOTE_ID])
     * advances only after a successful apply so unlock/hourly/daily triggers do
     * not get stuck on one wallpaper.
     */
    private suspend fun applyNextApprovedQuoteOnUnlock(context: Context) {
        val preferences = WallpaperPreferences(context)
        val settings = preferences.getSettings()
        if (!settings.isWallpaperEnabled ||
            settings.updateTrigger != WallpaperUpdateTrigger.ON_UNLOCK
        ) {
            return
        }
        val database = VibeReaderDatabase.getInstance(context)
        val repository = QuoteRepository(database)
        val nextQuote = repository.getNextApprovedQuote(preferences.getLastWallpaperQuoteId())
            ?: run {
                Log.w(TAG, "No approved quotes available for on-unlock rotation")
                return
            }

        val book = database.bookDao().getById(nextQuote.bookId) ?: run {
            Log.w(TAG, "Book missing for next approved quote id=${nextQuote.id}")
            return
        }
        val documentLoader = EBookDocumentLoader(context)
        val format = EBookFormat.fromLabel(book.format) ?: EBookFormat.fromFileName(book.filePath)
        val fallbackTitle = FilenameSanitizer.cleanBookTitle(book.title).ifBlank { "Untitled Book" }
        val file = book.filePath.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)
        val document = if (format != null && file != null) {
            try {
                documentLoader.loadDocument(
                    bookId = book.id,
                    file = file,
                    format = format,
                    fallbackTitle = fallbackTitle,
                    includeChapters = false,
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Using fallback document metadata for ${book.title}", error)
                EBookDocument(
                    bookId = book.id,
                    format = format,
                    filePath = book.filePath,
                    metadata = EBookMetadata(title = fallbackTitle, author = book.author),
                )
            }
        } else {
            EBookDocument(
                bookId = book.id,
                format = format ?: EBookFormat.PDF,
                filePath = book.filePath,
                metadata = EBookMetadata(title = fallbackTitle, author = book.author),
            )
        }

        val progress = database.readingProgressDao().getForBook(book.id)?.progress
            ?.coerceIn(0f, 1f) ?: 0f
        val progressPercent = (progress * 100).toInt()
        val safeTitle = FilenameSanitizer.cleanBookTitle(document.title).ifBlank { "Untitled Book" }
        val progressText = "Vibe Reader • $progressPercent% through $safeTitle"

        val bitmap = TypographyWallpaperGenerator.generateWallpaperBitmap(
            context = context,
            quote = nextQuote.selectedText.take(MAX_QUOTE_CHARS),
            bookTitle = document.title,
            bookAuthor = document.author,
            progressText = progressText,
            isDarkMode = settings.isDarkTheme,
        )
        try {
            try {
                val wallpaperManager = WallpaperManager.getInstance(context)
                when (settings.applyTarget) {
                    WallpaperTarget.HOMESCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    } else {
                        wallpaperManager.setBitmap(bitmap)
                    }

                    WallpaperTarget.LOCKSCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                    } else {
                        wallpaperManager.setBitmap(bitmap)
                    }

                    WallpaperTarget.BOTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                        wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                    } else {
                        wallpaperManager.setBitmap(bitmap)
                    }
                }
                preferences.saveLastWallpaperQuoteId(nextQuote.id)
                val preview = TypographyWallpaperGenerator.saveWallpaperPreview(context, bitmap)
                preferences.saveCurrentWallpaperPath(preview.absolutePath)
                Log.d(TAG, "On-unlock wallpaper applied (quoteId=${nextQuote.id})")
            } catch (error: Throwable) {
                Log.e(TAG, "WallpaperManager.setBitmap failed during on-unlock rotation", error)
            }
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun restoreWallpaperRotation(context: Context) {
        val settings = runCatching { WallpaperPreferences(context).getSettings() }.getOrNull()
        if (settings == null) {
            Log.w(TAG, "Could not read wallpaper settings while restoring rotation")
            return
        }
        Log.d(
            TAG,
            "Restoring wallpaper rotation: enabled=${settings.isWallpaperEnabled}, " +
                "trigger=${settings.updateTrigger}",
        )
        WallpaperRotationManager.updateFrequency(context, settings.updateTrigger)
    }

    companion object {
        private const val TAG = "WallpaperRotation"
        private const val MAX_QUOTE_CHARS = 1_200

        @Volatile
        private var instance: UnlockReceiver? = null
        private val lock = Any()

        /**
         * Safely registers a single process-wide [UnlockReceiver] on the
         * application context. Uses [ContextCompat.registerReceiver] with
         * [ContextCompat.RECEIVER_NOT_EXPORTED] for Android 13+ compatibility.
         */
        fun register(context: Context) {
            val appContext = context.applicationContext
            synchronized(lock) {
                if (instance != null) {
                    Log.d(TAG, "UnlockReceiver already registered")
                    return
                }
                val receiver = UnlockReceiver()
                instance = receiver
                try {
                    ContextCompat.registerReceiver(
                        appContext,
                        receiver,
                        IntentFilter(Intent.ACTION_USER_PRESENT),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    Log.d(TAG, "UnlockReceiver registered for ACTION_USER_PRESENT")
                } catch (error: Exception) {
                    instance = null
                    Log.e(TAG, "Failed to register UnlockReceiver", error)
                }
            }
        }

        /**
         * Safely unregisters the process-wide [UnlockReceiver], if present.
         */
        fun unregister(context: Context) {
            val appContext = context.applicationContext
            val receiver = synchronized(lock) {
                val current = instance ?: return
                instance = null
                current
            }
            try {
                appContext.unregisterReceiver(receiver)
                Log.d(TAG, "UnlockReceiver unregistered")
            } catch (error: Exception) {
                // Safe to ignore: it may already be unregistered or the process
                // is shutting down.
                Log.w(TAG, "UnlockReceiver unregister failed (possibly already unregistered)", error)
            }
        }
    }
}

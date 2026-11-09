package com.example.vibereader

import android.app.Application
import android.os.Looper
import android.util.Log
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.service.WallpaperRotationManager

/**
 * Process-level crash guard plus wallpaper rotation restoration.
 *
 * The reader already wraps its work in `guardedLaunch`, but failures can still
 * escape from third-party threads (OkHttp dispatchers, TTS callbacks,
 * WorkManager, PDFBox) and take the whole process down. A background thread
 * crash is recovered here; a main-thread crash cannot be resumed safely, so it
 * is logged and delegated to the platform handler.
 */
class VibeReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashGuard()
        restoreWallpaperRotationState()
    }

    private fun installCrashGuard() {
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "Uncaught exception on thread '${thread.name}'", error)
            if (thread === Looper.getMainLooper().thread) {
                // The UI thread is in an undefined state; let Android restart
                // the app instead of continuing with corrupted state.
                systemHandler?.uncaughtException(thread, error)
            } else {
                // One failed network callback must never close the reader.
                Log.w(TAG, "Recovered from background failure; process kept alive.")
            }
        }
    }

    /**
     * Restores wallpaper rotation from saved preferences on app/process start.
     *
     * Re-applies the saved trigger so periodic WorkManager jobs are scheduled and
     * the dynamic ACTION_USER_PRESENT receiver is registered when ON_UNLOCK is
     * active. This is idempotent and helps recover if work was lost or the app
     * was updated while in the background.
     */
    private fun restoreWallpaperRotationState() {
        val settings = runCatching { WallpaperPreferences(this).getSettings() }.getOrNull()
        if (settings == null) {
            Log.w(ROTATION_TAG, "Could not read wallpaper settings at app start")
            return
        }
        Log.d(
            ROTATION_TAG,
            "Restoring wallpaper rotation at app start: enabled=${settings.isWallpaperEnabled}, " +
                "trigger=${settings.updateTrigger}",
        )
        WallpaperRotationManager.updateFrequency(this, settings.updateTrigger)
    }

    private companion object {
        const val TAG = "VibeReaderCrashGuard"
        const val ROTATION_TAG = "WallpaperRotation"
    }
}

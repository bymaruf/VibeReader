package com.example.vibereader.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.example.vibereader.data.local.WallpaperTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object WallpaperApplyManager {
    suspend fun applyWallpaper(
        context: Context,
        bitmap: Bitmap,
        target: WallpaperTarget,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val wallpaperManager = WallpaperManager.getInstance(context.applicationContext)
        val failures = mutableListOf<Throwable>()

        fun trySet(flag: Int): Boolean = try {
            wallpaperManager.setBitmap(bitmap, null, true, flag)
            true
        } catch (error: Throwable) {
            failures += error
            Log.w(TAG, "WallpaperManager rejected flag=$flag for $target", error)
            false
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val applied = when (target) {
                    WallpaperTarget.HOMESCREEN ->
                        trySet(WallpaperManager.FLAG_SYSTEM)

                    // Lock-screen support varies by OEM: fall back to the
                    // system wallpaper instead of failing the whole action.
                    WallpaperTarget.LOCKSCREEN ->
                        trySet(WallpaperManager.FLAG_LOCK) ||
                            trySet(WallpaperManager.FLAG_SYSTEM)

                    WallpaperTarget.BOTH -> {
                        val system = trySet(WallpaperManager.FLAG_SYSTEM)
                        val lock = trySet(WallpaperManager.FLAG_LOCK)
                        system || lock
                    }
                }
                if (!applied) {
                    throw failures.lastOrNull()
                        ?: IOException("The device did not accept the wallpaper.")
                }
            } else {
                wallpaperManager.setBitmap(bitmap)
            }
            Result.success(Unit)
        } catch (error: SecurityException) {
            Log.e(TAG, "Wallpaper permission/security failure for $target", error)
            Result.failure(
                IOException(
                    "Wallpaper permission was denied. Allow VibeReader to set wallpapers and try again.",
                    error,
                ),
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to apply generated wallpaper for $target", error)
            val security = failures.firstOrNull { it is SecurityException }
            if (security != null) {
                Result.failure(
                    IOException(
                        "Wallpaper permission was denied. Allow VibeReader to set wallpapers and try again.",
                        security,
                    ),
                )
            } else {
                Result.failure(error)
            }
        }
    }

    private const val TAG = "WallpaperApplyManager"
}

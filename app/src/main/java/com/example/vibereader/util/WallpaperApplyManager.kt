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
        try {
            val wallpaperManager = WallpaperManager.getInstance(context.applicationContext)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                when (target) {
                    WallpaperTarget.HOMESCREEN ->
                        wallpaperManager.setBitmap(
                            bitmap,
                            null,
                            true,
                            WallpaperManager.FLAG_SYSTEM,
                        )

                    WallpaperTarget.LOCKSCREEN ->
                        wallpaperManager.setBitmap(
                            bitmap,
                            null,
                            true,
                            WallpaperManager.FLAG_LOCK,
                        )

                    WallpaperTarget.BOTH -> {
                        wallpaperManager.setBitmap(
                            bitmap,
                            null,
                            true,
                            WallpaperManager.FLAG_SYSTEM,
                        )
                        wallpaperManager.setBitmap(
                            bitmap,
                            null,
                            true,
                            WallpaperManager.FLAG_LOCK,
                        )
                    }
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
            Result.failure(error)
        }
    }

    private const val TAG = "WallpaperApplyManager"
}

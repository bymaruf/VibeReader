package com.example.vibereader.service

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.vibereader.data.local.WallpaperUpdateTrigger
import java.util.concurrent.TimeUnit

object WallpaperUpdateScheduler {
    private const val DAILY_WORK_NAME = "vibereader_daily_wallpaper_update"
    private const val HOURLY_WORK_NAME = "vibereader_hourly_wallpaper_update"
    private const val UNLOCK_WORK_NAME = "vibereader_unlock_wallpaper_update"
    const val MANUAL_WORK_NAME = "vibereader_manual_wallpaper_update"

    fun configure(context: Context, trigger: WallpaperUpdateTrigger, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(DAILY_WORK_NAME)
        workManager.cancelUniqueWork(HOURLY_WORK_NAME)
        if (!enabled) return
        when (trigger) {
            WallpaperUpdateTrigger.DAILY -> enqueuePeriodic(
                workManager,
                DAILY_WORK_NAME,
                1,
                TimeUnit.DAYS,
            )
            WallpaperUpdateTrigger.HOURLY -> enqueuePeriodic(
                workManager,
                HOURLY_WORK_NAME,
                1,
                TimeUnit.HOURS,
            )
            WallpaperUpdateTrigger.ON_UNLOCK,
            WallpaperUpdateTrigger.OFF,
            -> Unit
        }
    }

    fun enqueueForUnlock(context: Context) {
        enqueue(context)
    }

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<WallpaperUpdateWorker>()
            .setInputData(Data.Builder().putBoolean(WallpaperUpdateWorker.KEY_MANUAL_REQUEST, true).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<WallpaperUpdateWorker>()
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNLOCK_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun enqueuePeriodic(
        workManager: WorkManager,
        name: String,
        interval: Long,
        unit: TimeUnit,
    ) {
        val request = PeriodicWorkRequestBuilder<WallpaperUpdateWorker>(interval, unit)
            .build()
        workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

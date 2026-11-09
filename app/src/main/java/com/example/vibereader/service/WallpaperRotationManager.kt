package com.example.vibereader.service

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.local.WallpaperUpdateTrigger
import java.util.concurrent.TimeUnit

/**
 * Automatic wallpaper rotation engine.
 *
 * Drives the "AUTOMATIC UPDATE FREQUENCY" setting persisted in
 * [WallpaperPreferences]:
 *  - Manual (trigger OFF): cancel periodic rotation jobs and unregister the
 *    unlock receiver. The one-shot MANUAL_WORK_NAME render is never cancelled,
 *    so "Apply Current Wallpaper" still works in Manual mode.
 *  - Hourly / Daily: schedule a [WallpaperUpdateWorker] periodic request
 *    (1 hour / 24 hours) that renders/applies the next approved quote.
 *  - On unlock: register [UnlockReceiver] for [android.content.Intent.ACTION_USER_PRESENT];
 *    on unlock it enqueues a one-time wallpaper update. Periodic WorkManager
 *    tasks are cancelled while ON_UNLOCK is active.
 *
 * [updateFrequency] is intentionally idempotent: it is safe to call from every
 * recomposition and from [android.app.Application.onCreate].
 */
object WallpaperRotationManager {
    /** WorkManager tag used by automatic (non-manual) wallpaper work. */
    const val WORK_TAG = "WALLPAPER_ROTATION"

    const val DAILY_WORK_NAME = "vibereader_daily_wallpaper_update"
    const val HOURLY_WORK_NAME = "vibereader_hourly_wallpaper_update"
    const val UNLOCK_WORK_NAME = "vibereader_unlock_wallpaper_update"
    const val MANUAL_WORK_NAME = "vibereader_manual_wallpaper_update"

    const val DAILY_INTERVAL_HOURS = 24L
    const val HOURLY_INTERVAL_HOURS = 1L

    private const val TAG = "WallpaperRotation"

    /**
     * Applies the requested automatic update frequency.
     *
     * Calling this repeatedly with the same trigger is harmless: unique-work
     * enqueue with `UPDATE` replaces the schedule in place, and receiver
     * registration is idempotent.
     */
    fun updateFrequency(context: Context, frequency: WallpaperUpdateTrigger) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        Log.d(TAG, "updateFrequency($frequency)")

        when (frequency) {
            WallpaperUpdateTrigger.OFF -> {
                Log.d(TAG, "Manual mode selected: cancelling periodic work and unlock receiver")
                cancelWallpaperRotation(appContext)
            }

            WallpaperUpdateTrigger.HOURLY -> {
                // Switching away from ON_UNLOCK.
                UnlockReceiver.unregister(appContext)
                // Cancel periodic tasks/state that do not apply to hourly mode.
                workManager.cancelUniqueWork(DAILY_WORK_NAME)
                workManager.cancelUniqueWork(UNLOCK_WORK_NAME)
                schedulePeriodic(
                    workManager = workManager,
                    workName = HOURLY_WORK_NAME,
                    interval = HOURLY_INTERVAL_HOURS,
                    unit = TimeUnit.HOURS,
                )
            }

            WallpaperUpdateTrigger.DAILY -> {
                // Switching away from ON_UNLOCK.
                UnlockReceiver.unregister(appContext)
                workManager.cancelUniqueWork(HOURLY_WORK_NAME)
                workManager.cancelUniqueWork(UNLOCK_WORK_NAME)
                schedulePeriodic(
                    workManager = workManager,
                    workName = DAILY_WORK_NAME,
                    interval = DAILY_INTERVAL_HOURS,
                    unit = TimeUnit.HOURS,
                )
            }

            WallpaperUpdateTrigger.ON_UNLOCK -> {
                // ON_UNLOCK: register unlock receiver and cancel periodic WorkManager tasks.
                workManager.cancelUniqueWork(HOURLY_WORK_NAME)
                workManager.cancelUniqueWork(DAILY_WORK_NAME)
                UnlockReceiver.register(appContext)
                Log.d(TAG, "On-unlock mode ready; periodic tasks cancelled, waiting for ACTION_USER_PRESENT")
            }
        }
    }

    /**
     * Cancels every automatic rotation job (used by Manual mode).
     *
     * MANUAL_WORK_NAME is deliberately left alone: it belongs to the one-shot
     * "Apply Current Wallpaper" action, which must keep working while rotation
     * is off.
     */
    fun cancelWallpaperRotation(context: Context) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelUniqueWork(HOURLY_WORK_NAME)
        workManager.cancelUniqueWork(DAILY_WORK_NAME)
        workManager.cancelUniqueWork(UNLOCK_WORK_NAME)
        UnlockReceiver.unregister(appContext)
        Log.d(TAG, "Automatic rotation cancelled; manual one-shot work left untouched")
    }

    private fun schedulePeriodic(
        workManager: WorkManager,
        workName: String,
        interval: Long,
        unit: TimeUnit,
    ) {
        // No constraints here on purpose: charging/idle requirements would
        // silently delay rotation on many devices.
        val request = PeriodicWorkRequestBuilder<WallpaperUpdateWorker>(interval, unit)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            workName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        Log.d(TAG, "Scheduled '$workName' every $interval ${unit.name.lowercase()}")
    }

    /**
     * Compatibility wrapper for previous callers. Registers the unlock receiver.
     */
    fun registerUnlockReceiver(context: Context) {
        UnlockReceiver.register(context)
    }

    /**
     * Compatibility wrapper for previous callers. Unregisters the unlock receiver.
     */
    fun unregisterUnlockReceiver(context: Context) {
        UnlockReceiver.unregister(context)
    }

    /** Queues a one-time wallpaper update (manual render or unlock trigger). */
    fun enqueueNextWallpaperUpdate(context: Context, manual: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<WallpaperUpdateWorker>()
            .apply {
                if (manual) {
                    setInputData(
                        Data.Builder()
                            .putBoolean(WallpaperUpdateWorker.KEY_MANUAL_REQUEST, true)
                            .build(),
                    )
                }
            }
            .build()
        val workName = if (manual) MANUAL_WORK_NAME else UNLOCK_WORK_NAME
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName,
            if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "Enqueued one-time work '$workName' (manual=$manual)")
    }

    /**
     * Compatibility handler for [android.content.Intent.ACTION_USER_PRESENT].
     * Enqueues the next wallpaper update when ON_UNLOCK is active.
     */
    fun triggerNextWallpaperUpdate(context: Context) {
        try {
            val settings = WallpaperPreferences(context.applicationContext).getSettings()
            Log.d(
                TAG,
                "Unlock received: enabled=${settings.isWallpaperEnabled}, " +
                    "trigger=${settings.updateTrigger}",
            )
            if (
                settings.isWallpaperEnabled &&
                settings.updateTrigger == WallpaperUpdateTrigger.ON_UNLOCK
            ) {
                enqueueNextWallpaperUpdate(context.applicationContext)
            }
        } catch (error: Exception) {
            // A receiver must never crash the process.
            Log.e(TAG, "Unlock handler failed", error)
        }
    }

    /** Immediately queues a manual wallpaper render. */
    fun enqueueNow(context: Context) {
        Log.d(TAG, "Manual wallpaper render requested")
        enqueueNextWallpaperUpdate(context = context, manual = true)
    }
}

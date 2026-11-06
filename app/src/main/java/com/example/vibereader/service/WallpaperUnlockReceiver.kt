package com.example.vibereader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.local.WallpaperUpdateTrigger

class WallpaperUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        val settings = WallpaperPreferences(context).getSettings()
        if (settings.isWallpaperEnabled && settings.updateTrigger == WallpaperUpdateTrigger.ON_UNLOCK) {
            WallpaperUpdateScheduler.enqueueForUnlock(context)
        }
    }
}

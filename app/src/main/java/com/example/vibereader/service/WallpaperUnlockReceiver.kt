package com.example.vibereader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Compatibility shim kept because this class existed in the codebase.
 *
 * Unlock handling is implemented by [UnlockReceiver], which is registered
 * dynamically (ACTION_USER_PRESENT cannot be manifest-delivered on Android 8+).
 * This receiver is not declared in the manifest and should not be used for new
 * code.
 */
@Deprecated("Use UnlockReceiver for dynamic ACTION_USER_PRESENT handling.")
class WallpaperUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_PRESENT) return
        WallpaperRotationManager.enqueueNow(context.applicationContext)
    }
}

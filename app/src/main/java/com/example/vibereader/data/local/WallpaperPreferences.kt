package com.example.vibereader.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class WallpaperTarget(val label: String) {
    LOCKSCREEN("Lock screen"),
    HOMESCREEN("Home screen"),
    BOTH("Both screens"),
}

enum class WallpaperUpdateTrigger(val label: String) {
    DAILY("Daily"),
    HOURLY("Hourly"),
    ON_UNLOCK("On unlock"),
    OFF("Manual"),
}

data class WallpaperSettings(
    val isWallpaperEnabled: Boolean = false,
    val applyTarget: WallpaperTarget = WallpaperTarget.BOTH,
    val updateTrigger: WallpaperUpdateTrigger = WallpaperUpdateTrigger.DAILY,
    val currentPromptContext: String = "",
    val currentWallpaperPath: String? = null,
)

@Suppress("DEPRECATION")
class WallpaperPreferences(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFERENCES_FILE,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getSettings(): WallpaperSettings = WallpaperSettings(
        isWallpaperEnabled = preferences.getBoolean(KEY_ENABLED, false),
        applyTarget = enumValueOrDefault(
            preferences.getString(KEY_TARGET, null),
            WallpaperTarget.BOTH,
        ),
        updateTrigger = enumValueOrDefault(
            preferences.getString(KEY_TRIGGER, null),
            WallpaperUpdateTrigger.DAILY,
        ),
        currentPromptContext = preferences.getString(KEY_CONTEXT, "").orEmpty(),
        currentWallpaperPath = preferences.getString(KEY_WALLPAPER_PATH, null),
    )

    fun saveSettings(settings: WallpaperSettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, settings.isWallpaperEnabled)
            .putString(KEY_TARGET, settings.applyTarget.name)
            .putString(KEY_TRIGGER, settings.updateTrigger.name)
            .putString(KEY_CONTEXT, settings.currentPromptContext)
            .putString(KEY_WALLPAPER_PATH, settings.currentWallpaperPath)
            .apply()
    }

    fun savePromptContext(context: String) {
        preferences.edit().putString(KEY_CONTEXT, context).apply()
    }

    fun saveCurrentWallpaperPath(path: String) {
        preferences.edit().putString(KEY_WALLPAPER_PATH, path).apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String?,
        default: T,
    ): T = enumValues<T>().firstOrNull { it.name == value } ?: default

    private companion object {
        const val PREFERENCES_FILE = "vibereader_wallpaper_preferences"
        const val KEY_ENABLED = "wallpaper_enabled"
        const val KEY_TARGET = "wallpaper_target"
        const val KEY_TRIGGER = "wallpaper_trigger"
        const val KEY_CONTEXT = "wallpaper_prompt_context"
        const val KEY_WALLPAPER_PATH = "wallpaper_current_path"
    }
}

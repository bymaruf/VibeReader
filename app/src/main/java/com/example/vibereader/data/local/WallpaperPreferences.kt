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
    val isDarkTheme: Boolean = true,
    val selectedBookIds: Set<String> = emptySet(),
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
        isDarkTheme = preferences.getBoolean(KEY_DARK_THEME, true),
        selectedBookIds = preferences.getString(KEY_SELECTED_BOOKS, null)
            ?.split(',')
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty(),
    )

    fun saveSettings(settings: WallpaperSettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, settings.isWallpaperEnabled)
            .putString(KEY_TARGET, settings.applyTarget.name)
            .putString(KEY_TRIGGER, settings.updateTrigger.name)
            .putString(KEY_CONTEXT, settings.currentPromptContext)
            .putString(KEY_WALLPAPER_PATH, settings.currentWallpaperPath)
            .putBoolean(KEY_DARK_THEME, settings.isDarkTheme)
            .putString(KEY_SELECTED_BOOKS, settings.selectedBookIds.joinToString(","))
            .apply()
    }

    fun savePromptContext(context: String) {
        preferences.edit().putString(KEY_CONTEXT, context).apply()
    }

    fun saveCurrentWallpaperPath(path: String) {
        preferences.edit().putString(KEY_WALLPAPER_PATH, path).apply()
    }

    /**
     * Persists only the canvas theme. Using a single-key write avoids clobbering
     * other settings with a stale [WallpaperSettings] snapshot.
     */
    fun saveTheme(isDarkTheme: Boolean) {
        preferences.edit().putBoolean(KEY_DARK_THEME, isDarkTheme).apply()
    }

    /**
     * Cursor used by [com.example.vibereader.service.WallpaperUpdateWorker] so
     * every automatic run advances to the next approved quote instead of
     * repeatedly painting the newest one.
     */
    fun getLastWallpaperQuoteId(): Long = preferences.getLong(KEY_LAST_QUOTE_ID, NO_QUOTE_ID)

    fun saveLastWallpaperQuoteId(quoteId: Long) {
        preferences.edit().putLong(KEY_LAST_QUOTE_ID, quoteId).apply()
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
        const val KEY_DARK_THEME = "wallpaper_dark_theme"
        const val KEY_SELECTED_BOOKS = "wallpaper_selected_books"
        const val KEY_LAST_QUOTE_ID = "wallpaper_last_quote_id"
        const val NO_QUOTE_ID = -1L
    }
}

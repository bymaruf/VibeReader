package com.example.vibereader.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_theme_preferences")
data class AppThemePreferenceEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val themeOption: String,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

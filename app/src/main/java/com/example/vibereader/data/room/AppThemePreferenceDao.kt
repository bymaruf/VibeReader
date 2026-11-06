package com.example.vibereader.data.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AppThemePreferenceDao {
    @Query("SELECT * FROM app_theme_preferences WHERE id = :id")
    suspend fun getById(id: Int = AppThemePreferenceEntity.SINGLETON_ID): AppThemePreferenceEntity?

    @Upsert
    suspend fun upsert(preference: AppThemePreferenceEntity)
}

package com.example.vibereader.data.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ReaderPreferenceDao {
    @Query("SELECT * FROM reader_preferences WHERE id = :id")
    suspend fun getById(id: Int = ReaderPreferenceEntity.READER_PREFERENCE_ID): ReaderPreferenceEntity?

    @Upsert
    suspend fun upsert(preferences: ReaderPreferenceEntity)
}

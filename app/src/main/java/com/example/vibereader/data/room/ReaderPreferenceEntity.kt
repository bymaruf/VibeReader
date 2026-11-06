package com.example.vibereader.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reader_preferences")
data class ReaderPreferenceEntity(
    @PrimaryKey val id: Int = READER_PREFERENCE_ID,
    val scrollMode: String,
) {
    companion object {
        const val READER_PREFERENCE_ID = 0
    }
}

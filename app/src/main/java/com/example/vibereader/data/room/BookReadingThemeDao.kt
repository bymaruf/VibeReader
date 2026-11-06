package com.example.vibereader.data.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BookReadingThemeDao {
    @Query("SELECT * FROM book_reading_themes WHERE bookId = :bookId")
    suspend fun getForBook(bookId: String): BookReadingThemeEntity?

    @Upsert
    suspend fun upsert(preference: BookReadingThemeEntity)
}

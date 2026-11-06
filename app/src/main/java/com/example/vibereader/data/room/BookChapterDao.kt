package com.example.vibereader.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<BookChapterEntity>)

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getForBook(bookId: String): List<BookChapterEntity>

    @Query("DELETE FROM book_chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("SELECT DISTINCT bookId FROM book_chapters")
    suspend fun getBookIdsWithChapters(): List<String>
}

package com.example.vibereader.data.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun upsert(book: BookEntity)

    @Upsert
    suspend fun upsert(books: List<BookEntity>)

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, addedAt DESC")
    fun getAllBooksSortedByRecent(): Flow<List<BookEntity>>

    @Query("UPDATE books SET lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun updateLastReadAt(bookId: String, timestamp: Long)

    @Query("UPDATE books SET coverImagePath = :coverImagePath WHERE id = :bookId")
    suspend fun updateCoverImagePath(bookId: String, coverImagePath: String)

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getById(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE documentUri = :documentUri LIMIT 1")
    suspend fun getByDocumentUri(documentUri: String): BookEntity?

    @Query("SELECT * FROM books WHERE isDeviceScanned = 1")
    suspend fun getDeviceScannedBooks(): List<BookEntity>

    @Query("SELECT * FROM books")
    fun observeAllForProgress(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: String)
}

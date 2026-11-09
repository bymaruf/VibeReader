package com.example.vibereader.data.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface QuoteDao {
    @Upsert
    suspend fun upsert(quote: QuoteEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(quotes: List<QuoteEntity>): List<Long>

    @Query("SELECT * FROM quotes ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM quotes WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeForBook(bookId: String): Flow<List<QuoteEntity>>

    /** Snapshot used for dedupe and exclusion when appending AI batches. */
    @Query("SELECT * FROM quotes WHERE bookId = :bookId ORDER BY createdAt ASC, id ASC")
    suspend fun getForBook(bookId: String): List<QuoteEntity>

    /** Only quotes the user explicitly approved for wallpaper rendering. */
    @Query(
        "SELECT * FROM quotes WHERE isApprovedForWallpaper = 1 " +
            "ORDER BY createdAt DESC",
    )
    fun observeApproved(): Flow<List<QuoteEntity>>

    @Query(
        "SELECT * FROM quotes WHERE bookId = :bookId AND isApprovedForWallpaper = 1 " +
            "ORDER BY createdAt DESC",
    )
    suspend fun getApprovedForBook(bookId: String): List<QuoteEntity>

    @Query(
        "UPDATE quotes SET isApprovedForWallpaper = :approved WHERE id = :id",
    )
    suspend fun setApprovedForWallpaper(id: Long, approved: Boolean)

    /**
     * Universal lookup for quotes on a PDF page or an EPUB chapter, ordered by
     * their character offset inside that chapter.
     */
    @Query(
        "SELECT * FROM quotes WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "ORDER BY characterOffset ASC, createdAt ASC",
    )
    fun observeForChapter(bookId: String, chapterIndex: Int): Flow<List<QuoteEntity>>

    @Delete
    suspend fun delete(quote: QuoteEntity)

    @Query("DELETE FROM quotes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

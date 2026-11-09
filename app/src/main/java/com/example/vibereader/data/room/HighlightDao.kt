package com.example.vibereader.data.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    @Upsert
    suspend fun upsert(highlight: HighlightEntity)

    @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(): HighlightEntity?

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeForBook(bookId: String): Flow<List<HighlightEntity>>

    /**
     * Universal lookup used by both PDF pages and EPUB chapters.
     *
     * `chapterIndex` is the PDF page index or the EPUB spine index, so the
     * reader does not need to branch on the file format.
     */
    @Query(
        "SELECT * FROM highlights WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "ORDER BY characterOffset ASC, createdAt ASC",
    )
    fun getHighlightsForChapter(
        bookId: String,
        chapterIndex: Int,
    ): Flow<List<HighlightEntity>>

    /** Legacy PDF-specific query kept for compatibility with older call sites. */
    @Query(
        "SELECT * FROM highlights WHERE bookId = :bookId AND pageIndex = :pageIndex " +
            "ORDER BY createdAt ASC",
    )
    fun getHighlightsForPage(bookId: String, pageIndex: Int): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestForBook(bookId: String): HighlightEntity?

    @Delete
    suspend fun delete(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :highlightId")
    suspend fun deleteById(highlightId: Long)
}

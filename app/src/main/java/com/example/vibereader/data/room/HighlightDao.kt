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

    @Query(
        "SELECT * FROM highlights WHERE bookId = :bookId AND pageIndex = :pageIndex " +
            "ORDER BY createdAt ASC",
    )
    fun getHighlightsForPage(bookId: String, pageIndex: Int): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestForBook(bookId: String): HighlightEntity?

    @Delete
    suspend fun delete(highlight: HighlightEntity)
}

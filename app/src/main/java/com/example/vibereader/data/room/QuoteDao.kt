package com.example.vibereader.data.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface QuoteDao {
    @Upsert
    suspend fun upsert(quote: QuoteEntity)

    @Query("SELECT * FROM quotes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM quotes WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeForBook(bookId: String): Flow<List<QuoteEntity>>

    @Delete
    suspend fun delete(quote: QuoteEntity)
}

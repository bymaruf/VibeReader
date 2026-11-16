package com.example.vibereader.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AiResponseDao {
    @Query(
        "SELECT content FROM ai_response_cache " +
            "WHERE bookId = :bookId AND cacheKind = :cacheKind " +
            "AND queryHash = :queryHash LIMIT 1",
    )
    suspend fun get(bookId: String, cacheKind: String, queryHash: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(response: AiResponseEntity)

    @Query(
        "SELECT queryHash FROM ai_response_cache " +
            "WHERE bookId = :bookId AND cacheKind = :cacheKind " +
            "ORDER BY createdAt DESC LIMIT -1 OFFSET :keep",
    )
    suspend fun keysBeyondLimit(bookId: String, cacheKind: String, keep: Int): List<String>

    @Query(
        "DELETE FROM ai_response_cache " +
            "WHERE bookId = :bookId AND cacheKind = :cacheKind " +
            "AND queryHash IN (:queryHashes)",
    )
    suspend fun deleteKeys(bookId: String, cacheKind: String, queryHashes: List<String>)

    @Query("DELETE FROM ai_response_cache WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

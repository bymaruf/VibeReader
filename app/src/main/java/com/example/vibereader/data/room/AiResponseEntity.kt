package com.example.vibereader.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Local cache of AI-generated text.
 *
 * Keys are scoped to a book and a response kind (Ask Book, reader summaries,
 * quote batches) plus a hash of the question/page/context. Repeating the same
 * request returns the stored answer without any provider call.
 */
@Entity(
    tableName = "ai_response_cache",
    primaryKeys = ["bookId", "cacheKind", "queryHash"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "cacheKind"]),
    ],
)
data class AiResponseEntity(
    val bookId: String,
    val cacheKind: String,
    val queryHash: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)

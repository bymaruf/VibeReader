package com.example.vibereader.data.repository

import android.util.Log
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.room.AiResponseEntity
import java.security.MessageDigest

/**
 * Room-backed cache for AI responses. Every entry is keyed by book, response
 * kind, and a deterministic hash of the request, so repeated questions, page
 * summaries, and quote batches cost zero provider tokens.
 */
class AiResponseCache(
    private val database: VibeReaderDatabase,
) {
    suspend fun get(bookId: String, cacheKind: String, queryHash: String): String? {
        if (bookId.isBlank() || queryHash.isBlank()) return null
        return runCatching {
            database.aiResponseDao().get(bookId, cacheKind, queryHash)
        }
            .onFailure { Log.w(TAG, "AI response cache read failed.", it) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    suspend fun put(
        bookId: String,
        cacheKind: String,
        queryHash: String,
        content: String,
    ) {
        if (bookId.isBlank() || queryHash.isBlank() || content.isBlank()) return
        if (content.length > MAX_CONTENT_CHARS) return
        runCatching {
            val dao = database.aiResponseDao()
            dao.upsert(
                AiResponseEntity(
                    bookId = bookId,
                    cacheKind = cacheKind,
                    queryHash = queryHash,
                    content = content,
                ),
            )
            val overflow = dao.keysBeyondLimit(bookId, cacheKind, MAX_ENTRIES_PER_KIND)
            if (overflow.isNotEmpty()) {
                dao.deleteKeys(bookId, cacheKind, overflow)
            }
        }.onFailure {
            // A cache write must never turn a successful AI answer into an error.
            Log.w(TAG, "AI response cache write failed.", it)
        }
    }

    companion object {
        const val KIND_ASK = "ask"
        const val KIND_READER_TASK = "reader_task"
        const val KIND_QUOTES = "quotes"

        /** Stable SHA-256 hash used as the cache's query/page key. */
        fun hash(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEachIndexed { index, part ->
                if (index > 0) digest.update(0)
                digest.update(part.toByteArray(Charsets.UTF_8))
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String {
            val result = StringBuilder(size * 2)
            for (byte in this) {
                val value = byte.toInt() and 0xFF
                result.append(HEX[value ushr 4])
                result.append(HEX[value and 0x0F])
            }
            return result.toString()
        }

        private const val MAX_ENTRIES_PER_KIND = 80
        private const val MAX_CONTENT_CHARS = 100_000
        private const val TAG = "AiResponseCache"
        private val HEX = "0123456789abcdef".toCharArray()
    }
}

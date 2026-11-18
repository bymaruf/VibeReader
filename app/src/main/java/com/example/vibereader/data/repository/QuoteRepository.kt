package com.example.vibereader.data.repository

import androidx.room.withTransaction
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.data.room.QuoteSource
import com.example.vibereader.domain.QuoteQualityValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Single place where the quote library is read or mutated.
 *
 * The important contract is [appendAiQuotes]: a fetch only inserts new rows.
 * It never updates, clears or deletes existing quotes, so every previous quote
 * keeps its `isApprovedForWallpaper` state across fetches.
 */
class QuoteRepository(
    private val database: VibeReaderDatabase,
) {
    private val quoteDao = database.quoteDao()

    fun observeAll(): Flow<List<QuoteEntity>> = quoteDao.observeAll()

    fun observeApproved(): Flow<List<QuoteEntity>> = quoteDao.observeApproved()

    suspend fun getForBook(bookId: String): List<QuoteEntity> = quoteDao.getForBook(bookId)

    suspend fun getApprovedForBook(bookId: String): List<QuoteEntity> =
        quoteDao.getApprovedForBook(bookId)

    /**
     * Appends AI-fetched quotes alongside the existing library.
     *
     * Existing rows are only *read* for dedupe; they are never rewritten. New
     * quotes are stored unapproved so the human-in-the-loop wallpaper gate is
     * preserved, and they are always tagged with [QuoteSource.AI] so they can
     * never fall back to the default user-saved bucket.
     *
     * @return the number of genuinely new quotes inserted.
     */
    suspend fun appendAiQuotes(
        bookId: String,
        texts: List<String>,
        chapter: String? = null,
    ): Int = withContext(Dispatchers.IO) {
        database.withTransaction {
            val existingTexts = quoteDao.getForBook(bookId)
                .mapTo(mutableSetOf()) { normalize(it.selectedText) }
            val createdAt = System.currentTimeMillis()
            val newQuotes = buildList {
                texts.forEachIndexed { index, rawText ->
                    val text = rawText.trim().take(MAX_QUOTE_CHARS).trim()
                    if (text.isEmpty()) return@forEachIndexed
                    if (!existingTexts.add(normalize(text))) return@forEachIndexed
                    add(
                        QuoteEntity(
                            bookId = bookId,
                            selectedText = text,
                            chapter = chapter
                                ?.trim()
                                ?.take(MAX_CHAPTER_CHARS)
                                ?.ifBlank { null },
                            isApprovedForWallpaper = false,
                            // CRITICAL: AI-fetched quotes must never fall back to
                            // the default USER source; they belong to the AI bucket.
                            source = QuoteSource.AI,
                            // Keep the fetch order deterministic even when the
                            // whole batch lands in the same millisecond.
                            createdAt = createdAt + index,
                        ),
                    )
                }
            }
            if (newQuotes.isEmpty()) {
                0
            } else {
                quoteDao.insertAll(newQuotes)
                newQuotes.size
            }
        }
    }

    suspend fun setApprovedForWallpaper(id: Long, approved: Boolean) {
        quoteDao.setApprovedForWallpaper(id, approved)
    }

    /**
     * Returns the next approved quote for wallpaper rotation in deterministic
     * sequential (round-robin) order. Approved quotes are ordered by creation
     * time/id ascending; the quote after [lastQuoteId] is selected, wrapping to
     * the first when the end is reached or when [lastQuoteId] is missing.
     */
    suspend fun getNextApprovedQuote(lastQuoteId: Long): QuoteEntity? =
        withContext(Dispatchers.IO) {
            val approved = quoteDao.observeApproved()
                .first()
                // Legacy rows may predate the renderer's completeness rules.
                // Skipping them here keeps automatic rotation from repeatedly
                // failing on the same unrenderable quote.
                .filter { QuoteQualityValidator.isValidQuote(it.selectedText) }
            if (approved.isEmpty()) return@withContext null
            val ordered = approved.sortedWith(
                compareBy<QuoteEntity> { it.createdAt }.thenBy { it.id },
            )
            val lastIndex = ordered.indexOfFirst { it.id == lastQuoteId }
            if (lastIndex >= 0) {
                ordered[(lastIndex + 1) % ordered.size]
            } else {
                ordered.first()
            }
        }

    suspend fun delete(quote: QuoteEntity) {
        quoteDao.delete(quote)
    }

    suspend fun deleteById(id: Long) {
        quoteDao.deleteById(id)
    }

    private fun normalize(text: String): String =
        text.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    private companion object {
        const val MAX_QUOTE_CHARS = 4_000
        const val MAX_CHAPTER_CHARS = 240
        val WHITESPACE = Regex("""\s+""")
    }
}

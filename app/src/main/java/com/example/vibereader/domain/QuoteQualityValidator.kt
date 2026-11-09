package com.example.vibereader.domain

import com.example.vibereader.domain.model.EBookLocation
import com.example.vibereader.domain.model.EBookQuote

object QuoteQualityValidator {
    private val wordPattern = Regex("""[\p{L}\p{N}]+(?:['’’-][\p{L}\p{N}]+)*""")
    private val incompleteEnds = setOf(
        "a", "an", "and", "because", "but", "for", "from", "if", "in", "into",
        "nor", "of", "on", "or", "the", "to", "with",
    )

    fun isValidQuote(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_LENGTH) return false

        val words = wordPattern.findAll(trimmed).map { it.value }.toList()
        if (words.size < MIN_WORDS) return false

        val openingTrimmed = trimmed.trimStart(*OPENING_QUOTE_CHARS)
        val firstLetter = openingTrimmed.firstOrNull(Char::isLetter) ?: return false
        if (!firstLetter.isUpperCase()) return false

        val lastWord = words.last().lowercase()
        if (lastWord in incompleteEnds) return false

        val finalCharacter = trimmed.last()
        if (finalCharacter !in TERMINAL_PUNCTUATION) return false
        if (trimmed.endsWith(ELLIPSIS)) return false

        return true
    }

    /** Validates both the excerpt and its universal document location. */
    fun isValidQuote(quote: EBookQuote): Boolean =
        isValidQuote(quote.selectedText) && isValidLocation(quote.location)

    /**
     * Convenience overload for callers that persist the generic document
     * parameters directly instead of building an [EBookQuote] first.
     */
    fun isValidQuote(
        bookId: String,
        chapterIndex: Int,
        characterOffset: Int,
        selectedText: String,
    ): Boolean = isValidQuote(selectedText) && isValidLocation(
        EBookLocation(
            bookId = bookId,
            chapterIndex = chapterIndex,
            characterOffset = characterOffset,
        ),
    )

    fun isValidLocation(location: EBookLocation): Boolean = location.isValid

    private const val MIN_LENGTH = 20
    private const val MIN_WORDS = 4
    private const val ELLIPSIS = "..."
    private val OPENING_QUOTE_CHARS = charArrayOf('"', '\'', '“', '‘', '«', '(')
    private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', '"', '\'', '”', '’', '»', ')')
}

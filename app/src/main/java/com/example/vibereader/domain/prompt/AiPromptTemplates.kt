package com.example.vibereader.domain.prompt

import java.util.Locale

object AiPromptTemplates {

    /** Exact sentinel a model must return when it does not know a book. */
    const val UNKNOWN_BOOK = "UNKNOWN_BOOK"

    /** Shown instead of a raw sentinel when an online summary cannot be found. */
    const val UNKNOWN_BOOK_MESSAGE = "This book is not available on the internet."

    /**
     * Entire-book summary prompt. It intentionally contains only the title and
     * author: no chapter, page, or excerpt text may be sent for this action.
     */
    fun buildEntireBookSummarySystemPrompt(
        bookTitle: String,
        author: String?,
    ): String = """
        Provide a clear, structured summary of the published book "$bookTitle"${authorSuffix(author)} using your knowledge.
        If you do not recognize this book or have no reliable information about it, respond ONLY with: $UNKNOWN_BOOK
        Otherwise write a concise summary with a short overview followed by the main themes and plot arc.
    """.trimIndent()

    /**
     * Compact quote metadata prompt. It sends only the title and author so a
     * recognized book can be quoted from the model's built-in knowledge without
     * shipping any book text over the network.
     */
    fun buildCompactQuoteMetadataRequest(
        bookTitle: String,
        author: String?,
        count: Int,
        excludedQuotes: List<String> = emptyList(),
    ): String = """
        Give ${count.coerceIn(8, 12)} distinct, accurate, memorable quotes from "$bookTitle"${authorSuffix(author)}.
        Each must be 40-240 characters and a complete, self-contained sentence.
        Return only a JSON array: [{"quote":"exact wording","author":"${author?.trim().orEmpty()}"}]
        If unknown, reply $UNKNOWN_BOOK.${exclusionBlock(excludedQuotes)}
    """.trimIndent()

    fun buildHumanSummaryPrompt(text: String): String {
        return """
        Summarize the following passage as a thoughtful reading companion.

        RULES:
        - Highlight key emotional and logical takeaways in concise, natural paragraphs.
        - Do not use generic intros (e.g., avoid "This chapter discusses...").
        - Keep the writing smooth, engaging, and human-friendly.

        Content:
        "${AiContextSanitizer.sanitize(text)}"
        """.trimIndent()
    }

    /**
     * Zero-context book answer. The prompt deliberately contains only the
     * book's title and author; the model must use its own knowledge or return
     * [UNKNOWN_BOOK] so the caller can switch to local retrieval.
     */
    fun buildBookMetadataSystemPrompt(
        bookTitle: String,
        author: String?,
    ): String = """
        You are a concise literary expert.
        Book: "$bookTitle"${authorSuffix(author)}
        Use only your built-in knowledge of this published book.
        If you do not recognize the book or cannot answer reliably, reply exactly: $UNKNOWN_BOOK
        Otherwise answer in at most $MAX_ANSWER_WORDS words. No preamble, no repetition of the question.
    """.trimIndent()

    /**
     * Local-RAG answer prompt. Only the supplied passages may be used; the
     * caller has already selected and sanitized the top paragraphs.
     */
    fun buildBookRagSystemPrompt(
        bookTitle: String,
        author: String?,
        passages: String,
    ): String = """
        You are a concise literary tutor.
        Book: "$bookTitle"${authorSuffix(author)}
        Answer using only the passages below. If they do not contain the answer, say so briefly.
        Keep the answer under $MAX_ANSWER_WORDS words. Do not invent details.
        Passages:
        ${AiContextSanitizer.sanitize(passages)}
    """.trimIndent()

    /**
     * Legacy combined prompt kept for callers that still pass a question in the
     * system message. New code uses [buildBookRagSystemPrompt] and sends the
     * question as user content.
     */
    fun buildBookQaPrompt(
        bookTitle: String,
        author: String?,
        contextSnippet: String,
        userQuestion: String,
    ): String = buildString {
        append(
            buildBookRagSystemPrompt(
                bookTitle = bookTitle,
                author = author,
                passages = contextSnippet.take(MAX_RAG_CONTEXT_CHARS),
            ),
        )
        append("\nQuestion: ")
        append(userQuestion.trim())
    }

    fun buildQuoteSystemPrompt(): String = """
        You are a literary quote curator. Work silently; never reveal reasoning.
        Return only a clean JSON array of objects:
        [{"quote":"complete, meaningful quote copied faithfully from the book","author":"author name"}]
        Return 8 to 12 distinct quotes. Each must be verbatim, insightful, 40-240
        characters, and a complete sentence suitable for a lock screen.
        Do not add commentary, thinking traces, markdown fences, numbering, or invented text.
    """.trimIndent()

    /**
     * First quote attempt: title and author only. Models that recognize a
     * published book can quote it from their built-in knowledge; unknown books
     * return [UNKNOWN_BOOK] and the caller switches to local retrieval.
     */
    fun buildQuoteMetadataRequest(
        bookTitle: String,
        author: String?,
        count: Int,
        excludedQuotes: List<String> = emptyList(),
    ): String = """
        Book: "$bookTitle"${authorSuffix(author)}
        Using only your built-in knowledge of this published book, return ${count.coerceIn(8, 12)} distinct new quotes.
        If you do not recognize the book or cannot recall its exact text, reply exactly: $UNKNOWN_BOOK
        Return clean JSON: [{"quote":"exact wording","author":"author name"}].${exclusionBlock(excludedQuotes)}
    """.trimIndent()

    fun buildQuoteRagRequest(
        bookTitle: String,
        author: String?,
        passages: String,
        count: Int,
        excludedQuotes: List<String> = emptyList(),
    ): String = """
        Book: "$bookTitle"${authorSuffix(author)}
        Return ${count.coerceIn(8, 12)} distinct, complete, insightful quotes from the passages below.
        Copy the exact wording; do not paraphrase or invent text. Each quote must be
        a complete sentence of 40-240 characters suitable for a lock screen.
        Return only a clean JSON array: [{"quote":"exact wording","author":"author name"}].${exclusionBlock(excludedQuotes)}
        Passages:
        ${AiContextSanitizer.sanitize(passages)}
    """.trimIndent()

    /**
     * Last-resort prompt for books with no locally available text. It still
     * asks for exact quotes only and permits an empty array so the validator can
     * reject invented material.
     */
    fun buildQuoteBestEffortRequest(
        bookTitle: String,
        author: String?,
        count: Int,
        excludedQuotes: List<String> = emptyList(),
    ): String = """
        Book: "$bookTitle"${authorSuffix(author)}
        Return ${count.coerceIn(8, 12)} distinct, complete quotes you are confident actually appear in this book.
        Copy exact wording; never invent or paraphrase.
        If you cannot recall exact wording, return [].
        Return clean JSON: [{"quote":"exact wording","author":"author name"}].${exclusionBlock(excludedQuotes)}
    """.trimIndent()

    /**
     * True only when the model returned the sentinel, not when an answer merely
     * mentions the word. Fenced and quoted variants are tolerated.
     */
    fun isUnknownBookResponse(raw: String): Boolean {
        val cleaned = raw.trim()
            .trim('`', '"', '\'', ' ')
            .trim()
        if (cleaned.equals(UNKNOWN_BOOK, ignoreCase = true)) return true
        val firstLine = cleaned.lineSequence()
            .firstOrNull()
            ?.trim()
            ?.trim('`', '"', '\'', '.', ':', ' ')
            ?.trim()
            ?: return false
        return firstLine.equals(UNKNOWN_BOOK, ignoreCase = true) && cleaned.length <= MAX_UNKNOWN_FLAG_CHARS
    }

    fun containsUnknownBookFlag(raw: String): Boolean =
        raw.lowercase(Locale.ROOT).contains(UNKNOWN_BOOK.lowercase(Locale.ROOT))

    private fun authorSuffix(author: String?): String =
        author?.trim()?.takeIf(String::isNotBlank)?.let { " by $it" }.orEmpty()

    private fun exclusionBlock(excludedQuotes: List<String>): String {
        if (excludedQuotes.isEmpty()) return ""
        val lines = excludedQuotes
            .asSequence()
            .map(AiContextSanitizer::oneLine)
            .filter(String::isNotBlank)
            .map { "- ${it.take(MAX_EXCLUSION_LINE_CHARS)}" }
            .joinToString("\n")
        if (lines.isBlank()) return ""
        return "\nDo NOT repeat any of these existing quotes " +
            "(Do not repeat these already collected quotes):\n$lines"
    }

    private const val MAX_ANSWER_WORDS = 120
    private const val MAX_RAG_CONTEXT_CHARS = 2_500
    private const val MAX_EXCLUSION_LINE_CHARS = 70
    private const val MAX_UNKNOWN_FLAG_CHARS = 120
}

package com.example.vibereader.domain.prompt

import java.util.Locale
import kotlin.math.ln

/**
 * Dependency-free local retrieval for book text.
 *
 * Text is split into paragraph-sized chunks, tokenized, and scored with BM25.
 * Only the highest-scoring chunks are returned, capped by both snippet count and
 * total characters, so a request carries a few relevant paragraphs instead of an
 * entire chapter or book.
 */
object LocalKeywordRetriever {

    const val DEFAULT_MAX_SNIPPETS = 4
    const val DEFAULT_MAX_CHARS = 2_500

    /**
     * @param query the reader's question or quote intent.
     * @param sources full chapters/pages or the current reading context.
     * @param prioritySources text the reader is looking at right now (selection,
     * visible page, or current chapter). Matching chunks receive a score bonus.
     * @param excludePhrases passages already used, such as previously collected
     * quotes. Chunks containing them are skipped so quote fetching can rotate
     * through the book without sending everything again.
     * @param preferQuotes adds a small heuristic bonus for complete, reflective
     * paragraphs when the caller wants quotable material rather than an answer.
     */
    fun selectRelevantSnippets(
        query: String,
        sources: List<String>,
        prioritySources: List<String> = emptyList(),
        maxSnippets: Int = DEFAULT_MAX_SNIPPETS,
        maxChars: Int = DEFAULT_MAX_CHARS,
        excludePhrases: List<String> = emptyList(),
        preferQuotes: Boolean = false,
    ): List<String> {
        if (sources.isEmpty() && prioritySources.isEmpty()) return emptyList()

        val snippetLimit = maxSnippets.coerceAtLeast(1)
        val charLimit = maxChars.coerceAtLeast(1)
        val priorityTexts = prioritySources
            .map(AiContextSanitizer::sanitize)
            .filter(String::isNotBlank)
            .toSet()
        val allSources = buildList {
            addAll(priorityTexts)
            addAll(sources.map(AiContextSanitizer::sanitize))
        }
            .filter(String::isNotBlank)
            .distinct()
        val chunks = buildChunks(allSources, priorityTexts, preferQuotes)
        if (chunks.isEmpty()) return emptyList()

        val queryTerms = tokenize(query)
        val excluded = excludePhrases
            .map(::normalizeForMatch)
            .filter { it.length >= MIN_EXCLUDED_PHRASE_CHARS }

        val scored = if (queryTerms.isEmpty()) {
            chunks.map { chunk ->
                chunk to (if (chunk.priority) PRIORITY_BASE_SCORE else 0.0) +
                    if (preferQuotes) chunk.quoteScore else 0.0
            }
        } else {
            val documentFrequency = mutableMapOf<String, Int>()
            chunks.forEach { chunk ->
                chunk.termFrequencies.keys.forEach { term ->
                    if (term in queryTerms) {
                        documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
                    }
                }
            }
            val averageLength = chunks.sumOf { it.length }
                .toDouble()
                .div(chunks.size)
                .coerceAtLeast(1.0)
            chunks.map { chunk ->
                var score = 0.0
                queryTerms.forEach { term ->
                    val frequency = chunk.termFrequencies[term] ?: 0
                    if (frequency > 0) {
                        val frequencyInChunks = documentFrequency[term] ?: 0
                        val inverseDocumentFrequency = ln(
                            1.0 + (chunks.size - frequencyInChunks + 0.5) /
                                (frequencyInChunks + 0.5),
                        )
                        score += inverseDocumentFrequency *
                            (frequency * (BM25_K1 + 1.0)) /
                            (
                                frequency + BM25_K1 *
                                    (1.0 - BM25_B + BM25_B * chunk.length / averageLength)
                                )
                    }
                }
                if (score > 0.0 && chunk.priority) score += PRIORITY_SCORE_BONUS
                if (preferQuotes) score += chunk.quoteScore
                chunk to score
            }
        }

        val ranked = scored.sortedWith(
            compareByDescending<Pair<Chunk, Double>> { it.second }
                .thenByDescending { it.first.priority }
                .thenBy { it.first.order },
        )

        val selected = mutableListOf<String>()
        var usedChars = 0
        for ((chunk, _) in ranked) {
            if (selected.size >= snippetLimit) break
            if (isExcluded(chunk.text, excluded)) continue
            if (selected.any { it == chunk.text }) continue
            val remaining = charLimit - usedChars
            if (remaining <= MIN_SNIPPET_CHARS) break
            val snippet = if (chunk.text.length <= remaining) {
                chunk.text
            } else {
                AiContextSanitizer.truncateAtBoundary(chunk.text, remaining)
            }
            if (snippet.isBlank()) continue
            selected += snippet
            usedChars += snippet.length
        }

        // A query made only of stopwords (for example "what does this mean?")
        // should still return the priority passage instead of an empty answer.
        if (selected.isEmpty() && excluded.isEmpty()) {
            for (chunk in chunks) {
                if (selected.size >= snippetLimit) break
                val remaining = charLimit - usedChars
                if (remaining <= MIN_SNIPPET_CHARS) break
                val snippet = if (chunk.text.length <= remaining) {
                    chunk.text
                } else {
                    AiContextSanitizer.truncateAtBoundary(chunk.text, remaining)
                }
                if (snippet.isNotBlank()) {
                    selected += snippet
                    usedChars += snippet.length
                }
            }
        }
        return selected
    }

    private fun buildChunks(
        sources: List<String>,
        priorityTexts: Set<String>,
        preferQuotes: Boolean,
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var order = 0
        sources.forEach { source ->
            val priority = source in priorityTexts
            chunkSource(source).forEach { text ->
                val termFrequencies = tokenizeToCounts(text)
                if (termFrequencies.isEmpty()) return@forEach
                chunks += Chunk(
                    text = text,
                    priority = priority,
                    order = order++,
                    termFrequencies = termFrequencies,
                    length = termFrequencies.values.sum().coerceAtLeast(1),
                    quoteScore = if (preferQuotes) quoteWorthiness(text) else 0.0,
                )
            }
        }
        return chunks
    }

    private fun chunkSource(source: String): List<String> {
        val paragraphs = source
            .split(PARAGRAPH_BREAK)
            .map(::normalizeParagraph)
            .filter(String::isNotBlank)
        if (paragraphs.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotBlank()) {
                chunks += splitLongChunk(buffer.toString())
                buffer.clear()
            }
        }
        paragraphs.forEach { paragraph ->
            if (buffer.isNotEmpty() && buffer.length + paragraph.length + 2 > TARGET_CHUNK_CHARS) {
                flush()
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(paragraph)
        }
        flush()
        return chunks
    }

    private fun splitLongChunk(text: String): List<String> {
        val clean = normalizeParagraph(text)
        if (clean.length <= MAX_CHUNK_CHARS) return listOf(clean)

        val sentences = SENTENCE_BREAK.split(clean)
        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotBlank()) {
                chunks += buffer.toString().trim()
                buffer.clear()
            }
        }
        sentences.forEach { sentence ->
            val piece = sentence.trim()
            if (piece.isEmpty()) return@forEach
            if (piece.length > MAX_CHUNK_CHARS) {
                flush()
                var start = 0
                while (start < piece.length) {
                    var end = (start + TARGET_CHUNK_CHARS).coerceAtMost(piece.length)
                    if (end < piece.length) {
                        val wordBreak = piece.lastIndexOf(' ', end)
                        if (wordBreak > start + TARGET_CHUNK_CHARS / 2) end = wordBreak
                    }
                    val part = piece.substring(start, end).trim()
                    if (part.isNotBlank()) chunks += part
                    start = end
                }
                return@forEach
            }
            if (buffer.isNotEmpty() && buffer.length + piece.length + 1 > TARGET_CHUNK_CHARS) {
                flush()
            }
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(piece)
        }
        flush()
        return chunks.filter(String::isNotBlank)
    }

    private fun normalizeParagraph(raw: String): String = AiContextSanitizer
        .sanitize(raw)
        .replace('\n', ' ')
        .replace(INLINE_WHITESPACE, " ")
        .trim()

    private fun tokenize(text: String): Set<String> = TOKEN.findAll(text.lowercase(Locale.ROOT))
        .map(MatchResult::value)
        .filter(::isMeaningfulToken)
        .map(::stem)
        .filter(String::isNotEmpty)
        .toSet()

    private fun tokenizeToCounts(text: String): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        TOKEN.findAll(text.lowercase(Locale.ROOT)).forEach { match ->
            if (!isMeaningfulToken(match.value)) return@forEach
            val token = stem(match.value)
            if (token.isNotEmpty()) counts[token] = (counts[token] ?: 0) + 1
        }
        return counts
    }

    private fun isMeaningfulToken(token: String): Boolean =
        token.length >= MIN_TOKEN_LENGTH && token !in STOP_WORDS

    private fun stem(token: String): String {
        if (token.length <= 4 || token.all(Char::isDigit)) return token
        return when {
            token.endsWith("ies") -> token.dropLast(3) + "y"
            token.endsWith("ing") -> token.dropLast(3)
            token.endsWith("edly") -> token.dropLast(4)
            token.endsWith("ed") -> token.dropLast(2)
            token.endsWith("es") -> token.dropLast(2)
            token.endsWith("s") && !token.endsWith("ss") -> token.dropLast(1)
            else -> token
        }
    }

    private fun quoteWorthiness(text: String): Double {
        if (text.length < MIN_QUOTE_CANDIDATE_CHARS) return 0.0
        var score = 0.0
        if (text.length in 120..900) score += 0.8
        if (text.any { it == '.' || it == '!' || it == '?' }) score += 0.3
        val letters = text.count(Char::isLetter)
        val digits = text.count(Char::isDigit)
        if (letters > digits * 4) score += 0.2
        val words = text.split(INLINE_WHITESPACE).filter(String::isNotBlank)
        if (words.size >= 12) {
            val uniqueRatio = words
                .map { it.lowercase(Locale.ROOT) }
                .distinct()
                .size
                .toDouble() / words.size
            if (uniqueRatio > 0.65) score += 0.4
        }
        return score
    }

    private fun normalizeForMatch(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace(NON_ALPHANUMERIC, " ")
        .replace(INLINE_WHITESPACE, " ")
        .trim()

    private fun isExcluded(text: String, excludedPhrases: List<String>): Boolean {
        if (excludedPhrases.isEmpty()) return false
        val normalized = normalizeForMatch(text)
        return excludedPhrases.any(normalized::contains)
    }

    private data class Chunk(
        val text: String,
        val priority: Boolean,
        val order: Int,
        val termFrequencies: Map<String, Int>,
        val length: Int,
        val quoteScore: Double,
    )

    private const val BM25_K1 = 1.5
    private const val BM25_B = 0.75
    private const val PRIORITY_SCORE_BONUS = 2.5
    private const val PRIORITY_BASE_SCORE = 2.0
    private const val MIN_TOKEN_LENGTH = 3
    private const val MIN_SNIPPET_CHARS = 160
    private const val MIN_EXCLUDED_PHRASE_CHARS = 24
    private const val MIN_QUOTE_CANDIDATE_CHARS = 80
    private const val TARGET_CHUNK_CHARS = 600
    private const val MAX_CHUNK_CHARS = 900

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val INLINE_WHITESPACE = Regex("\\s+")
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n+")
    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")

    /**
     * Common English function words plus generic reading-app words. Removing
     * them lets "what does this mean?" fall through to the priority passage
     * instead of matching random occurrences of "mean" or "passage".
     */
    private val STOP_WORDS = setOf(
        "a", "about", "above", "after", "again", "against", "all", "also", "am",
        "an", "and", "any", "are", "aren", "as", "at", "be", "because", "been",
        "before", "being", "below", "between", "both", "but", "by", "can", "cannot",
        "could", "did", "do", "does", "doing", "don", "down", "during", "each",
        "few", "for", "from", "further", "had", "has", "have", "having", "he",
        "her", "here", "hers", "herself", "him", "himself", "his", "how", "i",
        "if", "in", "into", "is", "it", "its", "itself", "just", "me", "more",
        "most", "my", "myself", "no", "nor", "not", "now", "of", "off", "on",
        "once", "only", "or", "other", "our", "ours", "ourselves", "out", "over",
        "own", "please", "same", "she", "should", "so", "some", "such", "than",
        "that", "the", "their", "theirs", "them", "themselves", "then", "there",
        "these", "they", "this", "those", "through", "to", "too", "under", "until",
        "up", "very", "was", "we", "were", "what", "when", "where", "which",
        "while", "who", "whom", "whose", "why", "will", "with", "would", "you",
        "your", "yours", "yourself", "yourselves",
        // Reading-app vocabulary.
        "author", "book", "books", "chapter", "chapters", "describe", "explain",
        "happen", "happened", "happening", "happens", "mean", "meaning", "means",
        "page", "pages", "passage", "passages", "quote", "quotes", "reader",
        "reading", "say", "says", "section", "sections", "sentence", "sentences",
        "summarize", "summary", "tell", "text", "theme", "themes",
    )
}

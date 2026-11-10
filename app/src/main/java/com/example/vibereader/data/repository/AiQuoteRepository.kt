package com.example.vibereader.data.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.data.room.QuoteSource
import com.example.vibereader.domain.QuoteQualityValidator
import com.example.vibereader.model.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AiQuoteFetchResult(
    val insertedCount: Int,
    val provider: AiProvider,
    val model: String,
    val quotes: List<String>,
)

/**
 * Fetches AI-curated quotes, parses the provider response, maps the result to
 * [QuoteEntity] rows, and inserts only genuinely new quotes into Room.
 *
 * Configuration/key/model resolution:
 * - Inspects the active AI provider via [AiRuntimeResolver]/[AiKeyVault].
 * - Dynamically resolves the provider's API key and dedicated saved model
 *   (`SELECTED_OPENROUTER_MODEL`, `SELECTED_GEMINI_MODEL`,
 *   `SELECTED_OPENCODE_MODEL`, etc.).
 * - Validates key+model are non-blank before any network call; missing setup
 *   fails immediately with [AiRuntimeResolver.SETUP_ERROR_MESSAGE].
 *
 * Failover:
 * - [AiFailoverManager] tries the selected provider first, then enabled saved
 *   providers/keys, retrying on HTTP 401/403/404/429, any HTTP/network/timeout
 *   error, or unexpected runtime error.
 * - Each failover attempt uses the model saved for the provider being tried
 *   (re-resolved per request through the failover pool), never another
 *   provider's model ID.
 *
 * Rows are inserted one at a time and [onQuotePersisted] fires immediately
 * after each successful Room insert so the UI counter can update in real time.
 * Existing rows are never rewritten and new rows remain unapproved.
 */
class AiQuoteRepository(
    context: Context,
    private val database: VibeReaderDatabase,
    private val keyVault: AiKeyVault = AiKeyVault(context.applicationContext),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build(),
) {
    /** Centralized, thread-safe active AI config snapshot (IO-safe callers). */
    fun getActiveAiConfig(): ActiveAiConfig = AiRuntimeResolver.getActiveAiConfig(keyVault)

    /**
     * One-shot stop signal for the endless quote-fetch loop. After a terminal
     * failover failure the UI is allowed to show the error once; the next loop
     * iteration is cancelled instead of starting another provider sweep.
     */
    @Volatile
    private var stopNextFetchAtElapsed: Long = 0L

    /**
     * Fetches one batch and persists it. Throws [AiConfigurationException] or
     * [AiRequestException] with a user-friendly message when setup or the
     * network request fails. Any unexpected [Throwable] is logged with the
     * `VibeReader_AI` tag and converted into an [AiRequestException] so the UI
     * can always display something instead of failing silently.
     *
     * @param onQuotePersisted Invoked on the IO dispatcher after every row that
     * Room actually inserted. Duplicate rows skipped by the dedupe/conflict
     * rules never trigger the callback.
     */
    suspend fun fetchAndPersistQuotes(
        bookId: String,
        bookTitle: String,
        bookAuthor: String?,
        chapter: String? = null,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        alreadyCollected: List<String> = emptyList(),
        onQuotePersisted: ((QuoteEntity) -> Unit)? = null,
    ): AiQuoteFetchResult = withContext(Dispatchers.IO) {
        abortFetchLoopIfTerminal()
        try {
            fetchAndPersistQuotesInternal(
                bookId = bookId,
                bookTitle = bookTitle,
                bookAuthor = bookAuthor,
                chapter = chapter,
                batchSize = batchSize,
                alreadyCollected = alreadyCollected,
                onQuotePersisted = onQuotePersisted,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(AI_ERROR_TAG, "Execution error", error)
            val mapped = error.toQuoteFetchException()
            if (mapped is AiRequestException && mapped.stopBackgroundFetch) {
                // The caller has already received this failure. Arm a one-shot
                // cancellation so its automatic loop cannot immediately start
                // another full key sweep and spam the same Toast.
                stopNextFetchAtElapsed = SystemClock.elapsedRealtime()
            }
            throw mapped
        }
    }

    /**
     * Cancels exactly the next fetch attempt after a terminal failure. The
     * signal expires quickly so a deliberate user restart is never blocked.
     */
    private fun abortFetchLoopIfTerminal() {
        val armedAt = stopNextFetchAtElapsed
        if (armedAt == 0L) return
        stopNextFetchAtElapsed = 0L
        if (SystemClock.elapsedRealtime() - armedAt > TERMINAL_STOP_WINDOW_MILLIS) return
        throw CancellationException(
            "AI quote fetching stopped after all API keys failed.",
        )
    }

    private suspend fun fetchAndPersistQuotesInternal(
        bookId: String,
        bookTitle: String,
        bookAuthor: String?,
        chapter: String?,
        batchSize: Int,
        alreadyCollected: List<String>,
        onQuotePersisted: ((QuoteEntity) -> Unit)?,
    ): AiQuoteFetchResult {
        if (bookId.isBlank()) {
            throw AiConfigurationException("Open a book before fetching AI quotes.")
        }

        // Resolve synchronously before any network work. A missing key/model
        // throws the exact setup message; no request is sent with a blank model.
        val active = AiRuntimeResolver.resolveActive(keyVault)
        Log.d(
            TAG,
            "Starting quote fetch with ${active.provider.shortLabel}, " +
                "preference=${active.modelPreferenceKey}, model=${active.model}, " +
                "book=$bookId.",
        )

        val count = batchSize.coerceAtLeast(1)
        val authorHint = bookAuthor?.takeIf(String::isNotBlank)?.let { " by $it" }.orEmpty()
        val systemPrompt =
            "You are a literary quote curator. Return only a JSON array of strings. " +
                "Each string must be a complete, standalone quote copied faithfully from the book. " +
                "Do not add commentary, markdown fences, numbering, or invented text."
        val exclusion = alreadyCollected
            .asReversed()
            .take(MAX_EXCLUSION_QUOTES)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n") { "- ${it.take(MAX_EXCLUSION_CHARS)}" }
            ?.let { "\nDo not repeat these already collected quotes:\n$it" }
            .orEmpty()
        val userContent =
            "Find up to $count new, profound, complete, and highly meaningful quotes from " +
                "the book '$bookTitle'$authorHint. Return as a JSON array of strings. " +
                "If fewer new quotes remain, return as many as you can find.$exclusion"

        val completion = AiFailoverManager.complete(
            keyVault = keyVault,
            httpClient = httpClient,
            systemPrompt = systemPrompt,
            userContent = userContent,
            maxTokens = MAX_FETCH_TOKENS,
            temperature = 0.5,
            logTag = TAG,
            overallTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
        )
        Log.d(
            TAG,
            "Received ${completion.text.length} chars from ${completion.provider.shortLabel} " +
                "model=${completion.model}.",
        )

        val parsed = AiQuoteResponseParser.parse(completion.text)
        val quotes = parsed
            .map(::normalizeQuote)
            .filter(QuoteQualityValidator::isValidQuote)
            .distinctBy(::dedupeKey)
            .take(count)

        Log.d(
            TAG,
            "Parsed ${parsed.size} candidate(s), ${quotes.size} passed quote validation " +
                "for book=$bookId.",
        )

        if (quotes.isEmpty()) {
            Log.e(
                AI_ERROR_TAG,
                "Execution error: AI response contained no usable quotes. " +
                    "provider=${completion.provider.shortLabel}, " +
                    "model=${completion.model}, " +
                    "preview=${completion.text.take(PREVIEW_CHARS).replace('\n', ' ')}",
            )
            throw AiRequestException(
                message = "The AI returned no usable quotes. Try again or choose another " +
                    "model in Settings → AI Providers.",
                provider = completion.provider,
                model = completion.model,
                stopBackgroundFetch = true,
            )
        }

        val inserted = persistQuotes(
            bookId = bookId,
            quotes = quotes,
            chapter = chapter,
            onQuotePersisted = onQuotePersisted,
        )
        Log.d(
            TAG,
            "Parsed ${parsed.size} candidates, kept ${quotes.size}, inserted $inserted new " +
                "AI quote(s) for book=$bookId.",
        )
        return AiQuoteFetchResult(
            insertedCount = inserted,
            provider = completion.provider,
            model = completion.model,
            quotes = quotes,
        )
    }

    /**
     * Maps quote text to [QuoteEntity] rows and inserts each accepted row
     * immediately. The callback fires after Room returns a valid row ID, so the
     * UI counter can never get ahead of the database.
     */
    private suspend fun persistQuotes(
        bookId: String,
        quotes: List<String>,
        chapter: String?,
        onQuotePersisted: ((QuoteEntity) -> Unit)?,
    ): Int {
        val existing = database.quoteDao()
            .getForBook(bookId)
            .mapTo(mutableSetOf()) { dedupeKey(it.selectedText) }
        val createdAt = System.currentTimeMillis()
        val safeChapter = chapter
            ?.trim()
            ?.take(MAX_CHAPTER_CHARS)
            ?.ifBlank { null }

        var inserted = 0
        quotes.forEachIndexed { index, raw ->
            val text = normalizeQuote(raw).take(MAX_QUOTE_CHARS).trim()
            if (text.isEmpty() || !existing.add(dedupeKey(text))) {
                return@forEachIndexed
            }

            val entity = QuoteEntity(
                bookId = bookId,
                selectedText = text,
                chapter = safeChapter,
                isApprovedForWallpaper = false,
                source = QuoteSource.AI,
                createdAt = createdAt + index,
            )
            val rowIds = database.quoteDao().insertAll(listOf(entity))
            val rowId = rowIds.firstOrNull() ?: -1L
            if (rowId == -1L) {
                Log.d(TAG, "Skipped duplicate AI quote while inserting for book=$bookId.")
                return@forEachIndexed
            }

            inserted++
            val persisted = entity.copy(id = rowId)
            runCatching { onQuotePersisted?.invoke(persisted) }
                .onFailure { Log.w(TAG, "Quote progress callback failed.", it) }
            Log.d(
                TAG,
                "Persisted AI quote row=$rowId (#$inserted) for book=$bookId.",
            )
        }
        return inserted
    }

    private fun normalizeQuote(raw: String): String = raw
        .replace(WHITESPACE, " ")
        .trim()
        .trim('"', '\'', '“', '”', '‘', '’', ',', ';')

    private fun dedupeKey(text: String): String =
        text.replace(WHITESPACE, " ").trim().lowercase(Locale.ROOT)

    /**
     * Preserves the exact setup/all-keys messages while making sure an
     * unexpected failure is never silently swallowed by this repository.
     */
    private fun Throwable.toQuoteFetchException(): Throwable = when (this) {
        is AiConfigurationException,
        is AiRequestException,
        -> this

        else -> AiRequestException(
            message = "AI Error: " + (
                localizedMessage?.takeIf(String::isNotBlank)
                    ?: message?.takeIf(String::isNotBlank)
                    ?: javaClass.simpleName
                ),
            cause = this,
        )
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 10
        const val REQUEST_TIMEOUT_MILLIS = 90_000L
        private const val AI_ERROR_TAG = "VibeReader_AI"
        private const val MAX_EXCLUSION_QUOTES = 120
        private const val MAX_EXCLUSION_CHARS = 80
        private const val MAX_FETCH_TOKENS = 4096
        private const val MAX_QUOTE_CHARS = 4_000
        private const val MAX_CHAPTER_CHARS = 240
        private const val PREVIEW_CHARS = 400
        private const val TERMINAL_STOP_WINDOW_MILLIS = 2_000L
        private const val TAG = "AiQuoteRepository"
        private val WHITESPACE = Regex("""\s+""")
    }
}

/**
 * Parses the many response shapes models produce for quote requests:
 * a JSON string array, a fenced JSON array, an array of objects, an object
 * containing a `quotes`/`data` array, or a numbered plain-text list.
 *
 * The tiny JSON reader below is intentionally dependency-free so the exact
 * production parsing logic can be exercised by plain JVM unit tests.
 */
internal object AiQuoteResponseParser {
    fun parse(raw: String): List<String> {
        val cleaned = stripFences(raw)
        if (cleaned.isBlank()) return emptyList()

        val jsonQuotes = extractJson(cleaned)
            ?.let(::extractQuotes)
            .orEmpty()
        if (jsonQuotes.isNotEmpty()) return jsonQuotes
        return parsePlainText(cleaned)
    }

    private fun stripFences(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            val firstLineEnd = text.indexOf('\n')
            text = if (firstLineEnd >= 0) {
                text.substring(firstLineEnd + 1)
            } else {
                text
                    .removePrefix("```json")
                    .removePrefix("```JSON")
                    .removePrefix("```")
            }.trim()
        }
        if (text.endsWith("```")) {
            text = text.removeSuffix("```").trim()
        }
        return text
    }

    /** Tries every JSON-looking start so prose before the payload is tolerated. */
    private fun extractJson(text: String): Any? {
        for (start in text.indices) {
            if (text[start] != '[' && text[start] != '{') continue
            val value = runCatching { JsonScanner(text, start).parseValue() }.getOrNull()
            if (value is List<*> || value is Map<*, *>) return value
        }
        return null
    }

    private fun extractQuotes(root: Any?): List<String> = when (root) {
        is List<*> -> root.mapNotNull(::stringFromItem)
        is Map<*, *> -> quotesFromObject(root, depth = 0)
        else -> emptyList()
    }

    private fun quotesFromObject(map: Map<*, *>, depth: Int): List<String> {
        ARRAY_FIELDS.forEach { field ->
            when (val value = map[field]) {
                is List<*> -> {
                    val quotes = value.mapNotNull(::stringFromItem)
                    if (quotes.isNotEmpty()) return quotes
                }

                is Map<*, *> -> if (depth < MAX_OBJECT_DEPTH) {
                    val quotes = quotesFromObject(value, depth + 1)
                    if (quotes.isNotEmpty()) return quotes
                }
            }
        }
        STRING_FIELDS.forEach { field ->
            val quote = (map[field] as? String)
                ?.let(::cleanItem)
                ?.takeIf(String::isNotEmpty)
            if (quote != null) return listOf(quote)
        }
        return emptyList()
    }

    private fun stringFromItem(item: Any?): String? = when (item) {
        is String -> cleanItem(item).takeIf(String::isNotEmpty)
        is Map<*, *> -> STRING_FIELDS.firstNotNullOfOrNull { field ->
            (item[field] as? String)
                ?.let(::cleanItem)
                ?.takeIf(String::isNotEmpty)
        }

        else -> null
    }

    private fun parsePlainText(text: String): List<String> = text
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it.startsWith("{") || it.startsWith("[") || it.endsWith("}") }
        .map { line ->
            line
                .removePrefix("- ")
                .removePrefix("* ")
                .removePrefix("• ")
                .replace(NUMBER_PREFIX, "")
        }
        .map(::cleanItem)
        .filter { it.length >= MIN_PLAIN_TEXT_LENGTH }
        .toList()

    private fun cleanItem(raw: String): String = raw
        .replace(WHITESPACE, " ")
        .trim()
        .trim('"', '\'', '“', '”', '‘', '’', ',', ';')

    /**
     * Minimal recursive-descent JSON reader. It understands only values the
     * quote parser needs: objects, arrays, strings, numbers, booleans and null.
     */
    private class JsonScanner(
        private val text: String,
        start: Int,
    ) {
        private var position = start

        fun parseValue(): Any? {
            skipWhitespace()
            return when (val current = peek()) {
                '[' -> parseArray()
                '{' -> parseObject()
                '"' -> parseString()
                't' -> {
                    expectWord("true")
                    true
                }

                'f' -> {
                    expectWord("false")
                    false
                }

                'n' -> {
                    expectWord("null")
                    null
                }

                else -> {
                    if (current == '-' || current.isDigit()) parseNumber() else {
                        throw IllegalArgumentException("Unexpected JSON character '$current'.")
                    }
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val values = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return values
            }
            while (true) {
                values += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return values
                    }

                    else -> throw IllegalArgumentException("Expected ',' or ']'.")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val values = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return values
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                values[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return values
                    }

                    else -> throw IllegalArgumentException("Expected ',' or '}'.")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (position < text.length) {
                when (val current = text[position++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (position >= text.length) break
                        when (val escaped = text[position++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) {
                                    throw IllegalArgumentException("Invalid unicode escape.")
                                }
                                val hex = text.substring(position, position + 4)
                                position += 4
                                builder.append(hex.toInt(16).toChar())
                            }

                            else -> builder.append(escaped)
                        }
                    }

                    else -> builder.append(current)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string.")
        }

        private fun parseNumber(): Double {
            val start = position
            if (peek() == '-') position++
            while (position < text.length) {
                val current = text[position]
                if (current.isDigit() || current in ".eE+-") {
                    position++
                } else {
                    break
                }
            }
            return text.substring(start, position).toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid JSON number.")
        }

        private fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        private fun peek(): Char = text.getOrNull(position)
            ?: throw IllegalArgumentException("Unexpected end of JSON.")

        private fun expect(expected: Char) {
            skipWhitespace()
            if (peek() != expected) {
                throw IllegalArgumentException("Expected '$expected'.")
            }
            position++
        }

        private fun expectWord(word: String) {
            if (!text.startsWith(word, position)) {
                throw IllegalArgumentException("Expected '$word'.")
            }
            position += word.length
        }
    }

    private const val MIN_PLAIN_TEXT_LENGTH = 20
    private const val MAX_OBJECT_DEPTH = 2
    private val ARRAY_FIELDS = listOf("quotes", "data", "results", "items", "highlights")
    private val STRING_FIELDS = listOf("quote", "text", "content", "selectedText", "excerpt")
    private val NUMBER_PREFIX = Regex("""^\d+[.)]\s*""")
    private val WHITESPACE = Regex("""\s+""")
}

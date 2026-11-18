package com.example.vibereader.data.repository

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.document.EBookDocumentLoader
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.data.room.QuoteSource
import com.example.vibereader.domain.QuoteQualityValidator
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.prompt.AiContextSanitizer
import com.example.vibereader.domain.prompt.AiPromptTemplates
import com.example.vibereader.domain.prompt.AiResponseSanitizer
import com.example.vibereader.domain.prompt.LocalKeywordRetriever
import com.example.vibereader.model.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AiQuoteFetchResult(
    val insertedCount: Int,
    val provider: AiProvider?,
    val model: String?,
    val quotes: List<String>,
)

/** Result of the zero-context quote attempt. */
private data class AiQuoteAttempt(
    val quotes: List<String>,
    val unknownBook: Boolean,
)

/**
 * Internal attempt-level failure. A provider may return HTTP 200 with prose,
 * an empty array, or malformed JSON; that must fail over to the next provider
 * rather than surfacing immediately.
 */
private class AiQuoteValidationException(message: String) : Exception(message)

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
 * Failover and key rotation:
 * - [AiFailoverManager] resolves the whole provider/key pool per request and
 *   starts on the next entry from its process-wide round-robin cursor. Gemini
 *   contributes the saved key plus every build-time `GEMINI_API_KEYS` entry.
 * - HTTP 429 parks only that key for 60 seconds and immediately continues with
 *   the next key; HTTP 503 gets the same short cooldown.
 * - HTTP 401/403 parks the key for ten minutes, then it rejoins automatically.
 * - Timeouts fail over immediately; other network failures get up to two
 *   exponential backoff retries on the same key before the sweep advances.
 * - Parsing/validation failures are attempt failures too: an HTTP 200 response
 *   with no usable quotes advances to the next key instead of surfacing a
 *   provider-specific error immediately.
 * - Each failover attempt uses the model saved for the provider being tried
 *   (re-resolved per request through the failover pool), never another
 *   provider's model ID.
 *
 * Rows are inserted one at a time and [onQuotePersisted] fires immediately
 * after each successful Room insert so the UI counter can update in real time.
 * Existing rows are never rewritten. Callers control the initial approval
 * flag; Wallpaper Studio fetching passes `true` so every valid new quote
 * becomes a carousel card immediately.
 */
class AiQuoteRepository(
    context: Context,
    private val database: VibeReaderDatabase,
    private val keyVault: AiKeyVault = AiKeyVault(context.applicationContext),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val documentLoader: EBookDocumentLoader = EBookDocumentLoader(context.applicationContext),
    private val responseCache: AiResponseCache = AiResponseCache(database),
) {
    private val appContext: Context = context.applicationContext
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
        approveForWallpaper: Boolean = false,
        chunkIndex: Int = 0,
        chunkCount: Int = 1,
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
                approveForWallpaper = approveForWallpaper,
                chunkIndex = chunkIndex,
                chunkCount = chunkCount,
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
        approveForWallpaper: Boolean,
        chunkIndex: Int,
        chunkCount: Int,
        onQuotePersisted: ((QuoteEntity) -> Unit)?,
    ): AiQuoteFetchResult {
        if (bookId.isBlank()) {
            throw AiConfigurationException("Open a book before fetching AI quotes.")
        }

        purgeLeakedQuotes()

        // No single key is resolved here: the failover manager resolves the
        // whole pool, starts at the round-robin cursor, and throws the setup
        // error before any network call when the pool is empty.
        Log.d(TAG, "Starting quote fetch for book=$bookId.")

        val count = batchSize.coerceAtLeast(1)
        val safeTitle = bookTitle.trim().ifBlank { "this book" }
        val author = bookAuthor?.trim()?.takeIf(String::isNotBlank)
        val exclusions = recentExclusions(alreadyCollected)
        val cacheHash = AiResponseCache.hash(
            "quotes",
            safeTitle,
            author.orEmpty(),
            count.toString(),
            "chunk=$chunkIndex/$chunkCount",
            exclusions.joinToString("\u0000"),
        )
        val existingKeys = database.quoteDao()
            .getForBook(bookId)
            .mapTo(mutableSetOf()) { dedupeKey(it.selectedText) }
        alreadyCollected.forEach { existingKeys += dedupeKey(it) }

        val cachedQuotes = readCachedQuotes(bookId, cacheHash, existingKeys, count)
        if (cachedQuotes.isNotEmpty()) {
            Log.d(TAG, "Quote batch served from Room cache for book=$bookId.")
            return persistResult(
                bookId = bookId,
                quotes = cachedQuotes,
                chapter = chapter,
                provider = null,
                model = null,
                onQuotePersisted = onQuotePersisted,
                approveForWallpaper = approveForWallpaper,
            )
        }

        // 1) Zero-context: title and author only. A model that recognizes the
        //    published book can quote it from built-in knowledge; UNKNOWN_BOOK
        //    switches to local paragraph retrieval below. Later chunks skip
        //    this path so they always mine their own section of the book.
        //    A terminal metadata failure is remembered, not thrown: the local
        //    text path gets its own failover sweep, and a textless/scanned PDF
        //    still receives synthesized thematic quotes instead of zero rows.
        var metadataFailure: AiRequestException? = null
        val metadata = if (chunkIndex == 0) {
            try {
                tryMetadataQuotes(
                    bookId = bookId,
                    bookTitle = safeTitle,
                    author = author,
                    count = count,
                    excludedQuotes = exclusions,
                )
            } catch (error: AiRequestException) {
                metadataFailure = error
                Log.w(
                    TAG,
                    "Metadata quote pass failed for '$safeTitle'; continuing with " +
                        "local retrieval/fallback.",
                    error,
                )
                null
            }
        } else {
            null
        }
        val metadataQuotes = metadata?.value?.quotes
            .orEmpty()
            .filterNot { dedupeKey(it) in existingKeys }
        // A single metadata answer must not suppress the richer local RAG
        // request. Only short-circuit when the model already delivered a full
        // batch; otherwise combine both sources below.
        if (metadataQuotes.size >= count) {
            cacheQuotes(bookId, cacheHash, metadataQuotes)
            return persistResult(
                bookId = bookId,
                quotes = metadataQuotes,
                chapter = chapter,
                provider = metadata?.provider,
                model = metadata?.model,
                onQuotePersisted = onQuotePersisted,
                approveForWallpaper = approveForWallpaper,
            )
        }
        if (metadataQuotes.isNotEmpty()) {
            Log.d(
                TAG,
                "Metadata supplied ${metadataQuotes.size}/${count} quotes for " +
                    "'$safeTitle'; continuing with local retrieval.",
            )
        }
        if (metadata?.value?.unknownBook == true) {
            Log.d(
                TAG,
                "Model reported UNKNOWN_BOOK for '$safeTitle'; using local retrieval.",
            )
        }

        // 2) Local BM25 retrieval: four sanitized paragraphs, ~2,500 chars.
        val snippets = loadLocalQuoteSnippets(
            bookId = bookId,
            bookTitle = safeTitle,
            author = author,
            alreadyCollected = alreadyCollected,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
        )

        // A scanned/image-only PDF has no local text. If the metadata pass
        // already exhausted the provider pool, go straight to synthesized
        // quotes instead of spending another full network timeout.
        if (snippets.isEmpty() && metadataFailure != null) {
            val fallbackQuotes = fallbackQuotesFor(
                bookId = bookId,
                bookTitle = safeTitle,
                author = author,
                chapter = chapter,
                excluded = exclusions,
                count = count,
                alreadyAccepted = metadataQuotes,
                existingKeys = existingKeys,
            )
            if (fallbackQuotes.isNotEmpty()) {
                cacheQuotes(bookId, cacheHash, fallbackQuotes)
                return persistResult(
                    bookId = bookId,
                    quotes = fallbackQuotes,
                    chapter = chapter,
                    provider = metadata?.provider,
                    model = metadata?.model,
                    onQuotePersisted = onQuotePersisted,
                    approveForWallpaper = approveForWallpaper,
                )
            }
            throw metadataFailure
        }

        // The metadata sweep already exhausted every healthy key. Local text
        // exists, so a second sweep would burn another full timeout only to
        // fail identically; surface the real auth/quota error immediately.
        if (snippets.isNotEmpty() && metadataFailure?.stopBackgroundFetch == true) {
            throw metadataFailure
        }

        val userContent = if (snippets.isNotEmpty()) {
            AiPromptTemplates.buildQuoteRagRequest(
                bookTitle = safeTitle,
                author = author,
                passages = snippets.joinToString("\n\n"),
                count = count,
                excludedQuotes = exclusions,
            )
        } else {
            // PDFs and older imports may have no stored text. Keep the previous
            // best-effort behavior, but still cap output and reject invented
            // quotes through the normal validator.
            Log.d(TAG, "No local text for '$safeTitle'; using best-effort metadata.")
            AiPromptTemplates.buildQuoteBestEffortRequest(
                bookTitle = safeTitle,
                author = author,
                count = count,
                excludedQuotes = exclusions,
            )
        }
        val completion = try {
            requestQuoteBatch(
                userContent = userContent,
                count = count,
                bookId = bookId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AiRequestException) {
            // Every candidate answered but no response passed quote validation.
            // Metadata quotes already accepted by the same pipeline are kept;
            // for textless books (scanned PDFs) thematic reflections prevent a
            // permanent 0-result session. Auth/quota/network errors still pass
            // through untouched for books that do have readable text.
            val unusableResponse = error.responseRejected || snippets.isEmpty()
            if (!unusableResponse) throw error
            Log.w(TAG, "No usable AI quotes for '$safeTitle'; using fallback quotes.", error)
            val fallbackQuotes = fallbackQuotesFor(
                bookId = bookId,
                bookTitle = safeTitle,
                author = author,
                chapter = chapter,
                excluded = exclusions,
                count = count,
                alreadyAccepted = metadataQuotes,
                existingKeys = existingKeys,
            )
            if (fallbackQuotes.isEmpty()) throw error
            cacheQuotes(bookId, cacheHash, fallbackQuotes)
            return persistResult(
                bookId = bookId,
                quotes = fallbackQuotes,
                chapter = chapter,
                provider = metadata?.provider,
                model = metadata?.model,
                onQuotePersisted = onQuotePersisted,
                approveForWallpaper = approveForWallpaper,
            )
        }
        val combined = (metadataQuotes + completion.value)
            .map(::normalizeQuote)
            .filter(QuoteQualityValidator::isValidQuote)
            .filterNot { dedupeKey(it) in existingKeys }
            .distinctBy(::dedupeKey)
            .take(count)
        val quotes = if (combined.isEmpty() && snippets.isEmpty()) {
            Log.d(TAG, "AI returned no usable quotes for '$safeTitle'; using fallback quotes.")
            fallbackQuotesFor(
                bookId = bookId,
                bookTitle = safeTitle,
                author = author,
                chapter = chapter,
                excluded = exclusions,
                count = count,
                alreadyAccepted = metadataQuotes,
                existingKeys = existingKeys,
            )
        } else {
            combined
        }
        cacheQuotes(bookId, cacheHash, quotes)
        return persistResult(
            bookId = bookId,
            quotes = quotes,
            chapter = chapter,
            provider = completion.provider,
            model = completion.model,
            onQuotePersisted = onQuotePersisted,
            approveForWallpaper = approveForWallpaper,
        )
    }

    /**
     * Deletes old AI rows created from model reasoning/thinking text. Runs
     * before every fetch so bad cards disappear without a database migration.
     * User-saved quotes are never touched: a human highlight that happens to
     * contain a marker word is still legitimate.
     */
    private suspend fun purgeLeakedQuotes() {
        try {
            val dao = database.quoteDao()
            val leaked = dao.observeAll()
                .first()
                .filter { it.source == QuoteSource.AI }
                .filter {
                    AiQuoteResponseParser.isLeakText(it.selectedText) ||
                        AiResponseSanitizer.isArtifact(it.selectedText)
                }
            if (leaked.isEmpty()) return
            leaked.forEach { dao.delete(it) }
            Log.d(TAG, "Removed ${leaked.size} leaked reasoning quote(s).")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Could not purge leaked reasoning quotes.", error)
        }
    }

    private suspend fun tryMetadataQuotes(
        bookId: String,
        bookTitle: String,
        author: String?,
        count: Int,
        excludedQuotes: List<String>,
    ): AiValidatedCompletion<AiQuoteAttempt>? = try {
        AiFailoverManager.completeValidated(
            keyVault = keyVault,
            httpClient = httpClient,
            systemPrompt = AiPromptTemplates.buildQuoteSystemPrompt(),
            userContent = AiPromptTemplates.buildCompactQuoteMetadataRequest(
                bookTitle = bookTitle,
                author = author,
                count = count,
                excludedQuotes = excludedQuotes,
            ),
            maxTokens = MAX_FETCH_TOKENS,
            temperature = 0.7,
            logTag = TAG,
            overallTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
            validate = { raw ->
                if (AiPromptTemplates.isUnknownBookResponse(raw)) {
                    AiQuoteAttempt(quotes = emptyList(), unknownBook = true)
                } else {
                    AiQuoteAttempt(
                        quotes = extractUsableQuotes(raw, count, bookId),
                        unknownBook = false,
                    )
                }
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (config: AiConfigurationException) {
        throw config
    } catch (error: AiRequestException) {
        if (error.stopBackgroundFetch) throw error
        Log.w(TAG, "Metadata quote attempt failed; using local retrieval.", error)
        null
    } catch (error: Throwable) {
        Log.w(TAG, "Metadata quote attempt failed; using local retrieval.", error)
        null
    }

    private suspend fun requestQuoteBatch(
        userContent: String,
        count: Int,
        bookId: String,
    ): AiValidatedCompletion<List<String>> = AiFailoverManager.completeValidated(
        keyVault = keyVault,
        httpClient = httpClient,
        systemPrompt = AiPromptTemplates.buildQuoteSystemPrompt(),
        userContent = userContent,
        maxTokens = MAX_FETCH_TOKENS,
        temperature = 0.7,
        logTag = TAG,
        overallTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
        validate = { raw -> extractUsableQuotes(raw, count, bookId) },
    )

    private suspend fun readCachedQuotes(
        bookId: String,
        cacheHash: String,
        existingKeys: Set<String>,
        count: Int,
    ): List<String> {
        val cached = responseCache.get(bookId, AiResponseCache.KIND_QUOTES, cacheHash)
            ?: return emptyList()
        return AiQuoteResponseParser.parse(cached)
            .map(::normalizeQuote)
            .filter { it.length in MIN_QUOTE_CHARS..MAX_QUOTE_CHARS }
            .filterNot(AiResponseSanitizer::isArtifact)
            .filter(QuoteQualityValidator::isValidQuote)
            .distinctBy(::dedupeKey)
            .filterNot { dedupeKey(it) in existingKeys }
            .take(count)
    }

    private suspend fun cacheQuotes(
        bookId: String,
        cacheHash: String,
        quotes: List<String>,
    ) {
        if (quotes.isEmpty()) return
        val encoded = runCatching { JSONArray(quotes).toString() }.getOrNull() ?: return
        responseCache.put(
            bookId = bookId,
            cacheKind = AiResponseCache.KIND_QUOTES,
            queryHash = cacheHash,
            content = encoded,
        )
    }

    private fun recentExclusions(alreadyCollected: List<String>): List<String> =
        alreadyCollected
            // Callers pass the DAO's newest-first snapshot. Taking the first
            // entries excludes the most recently saved quotes, which are the
            // ones the model is most likely to repeat.
            .asSequence()
            .map(AiContextSanitizer::oneLine)
            .filter(String::isNotBlank)
            .map { it.take(MAX_EXCLUSION_CHARS) }
            .distinctBy(::dedupeKey)
            .take(MAX_EXCLUSION_QUOTES)
            .toList()

    private suspend fun loadLocalQuoteSnippets(
        bookId: String,
        bookTitle: String,
        author: String?,
        alreadyCollected: List<String>,
        chunkIndex: Int,
        chunkCount: Int,
    ): List<String> {
        val sources = loadBookSources(bookId, chunkIndex, chunkCount)
        if (sources.isEmpty()) return emptyList()
        val query = buildString {
            append(bookTitle)
            author?.let {
                append(' ')
                append(it)
            }
            append(" meaningful memorable profound quote")
        }
        return LocalKeywordRetriever.selectRelevantSnippets(
            query = query,
            sources = sources,
            maxSnippets = RETRIEVAL_SNIPPETS,
            maxChars = RETRIEVAL_CHARS,
            excludePhrases = alreadyCollected
                .take(MAX_EXCLUSION_QUOTES),
            preferQuotes = true,
        )
    }

    private suspend fun loadBookSources(
        bookId: String,
        chunkIndex: Int,
        chunkCount: Int,
    ): List<String> {
        val stored = database.bookChapterDao()
            .getForBook(bookId)
            .asSequence()
            .map { it.text }
            .filter(String::isNotBlank)
            .toList()
        if (stored.isNotEmpty()) {
            val sampled = sampleTextUnits(stored.asSequence(), chunkIndex, chunkCount)
            if (sampled.isNotEmpty()) return sampled
        }

        return try {
            withTimeoutOrNull(LOCAL_TEXT_TIMEOUT_MILLIS) {
                val book = database.bookDao().getById(bookId)
                    ?: return@withTimeoutOrNull emptyList()
                val file = resolveBookFile(book)
                    ?: return@withTimeoutOrNull emptyList()
                val format = EBookFormat.fromLabel(book.format)
                    ?: return@withTimeoutOrNull emptyList()
                when (format) {
                    EBookFormat.EPUB -> sampleTextUnits(
                        documentLoader.loadDocument(
                            bookId = bookId,
                            file = file,
                            format = format,
                            fallbackTitle = book.title,
                            includeChapters = true,
                        ).chapters.asSequence().map { it.text },
                        chunkIndex,
                        chunkCount,
                    )

                    EBookFormat.PDF -> samplePdfPages(
                        book = book,
                        file = file,
                        chunkIndex = chunkIndex,
                        chunkCount = chunkCount,
                    )
                }
            }.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Local quote retrieval could not read book=$bookId.", error)
            emptyList()
        }
    }

    /**
     * Resolves a readable source file for a book. Most imports store a real
     * `filePath`, but scans/older rows may only have a SAF `documentUri`; copy
     * that stream into the app cache once so EPUB/PDF text extraction still
     * works instead of silently sending zero context to the AI.
     */
    private fun resolveBookFile(book: BookEntity): File? {
        val direct = book.filePath
            .takeIf(String::isNotBlank)
            ?.let(::File)
        if (direct != null && direct.isFile && direct.length() > 0) return direct

        val uriString = book.documentUri.takeIf(String::isNotBlank) ?: return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val extension = book.format
            .lowercase(Locale.US)
            .filter(Char::isLetterOrDigit)
            .ifBlank { "bin" }
        val cached = File(
            File(appContext.cacheDir, QUOTE_SOURCE_CACHE_DIR),
            "quote-source-${book.id}.$extension",
        )
        if (cached.isFile && cached.length() > 0) return cached

        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                cached.parentFile?.mkdirs()
                cached.outputStream().use { output -> input.copyTo(output) }
            }
            cached.takeIf { it.isFile && it.length() > 0 }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Could not open documentUri for book=${book.id}.", error)
            cached.delete()
            null
        }
    }

    /**
     * Reads one non-overlapping PDF page window for [chunkIndex]. The page
     * count comes from the PDF header, so every chunk is proportional to the
     * real document instead of being capped at the first 32 pages. Blank,
     * cover, and table-of-contents pages are skipped until enough readable
     * text is collected.
     */
    private suspend fun samplePdfPages(
        book: BookEntity,
        file: File,
        chunkIndex: Int,
        chunkCount: Int,
    ): List<String> {
        val totalPages = try {
            documentLoader.loadDocument(
                bookId = book.id,
                file = file,
                format = EBookFormat.PDF,
                fallbackTitle = book.title,
                includeChapters = false,
            ).metadata.pageCount ?: 0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read PDF page count for book=${book.id}.", error)
            0
        }
        val chunks = chunkCount.coerceAtLeast(1)
        val index = chunkIndex.coerceAtLeast(0)
        if (index >= chunks) return emptyList()

        val pagesPerChunk = if (totalPages > 0) {
            // Divide the whole document into exactly [chunks] windows. A short
            // PDF therefore still produces several distinct samples instead of
            // one huge first window and empty later chunks.
            ((totalPages + chunks - 1) / chunks).coerceAtLeast(1)
        } else {
            PDF_PAGES_PER_CHUNK
        }
        val firstPage = index * pagesPerChunk
        if (totalPages > 0 && firstPage >= totalPages) return emptyList()
        val lastPage = if (totalPages > 0) {
            (firstPage + pagesPerChunk).coerceAtMost(totalPages)
        } else {
            firstPage + pagesPerChunk
        }

        val pages = mutableListOf<String>()
        var characters = 0
        for (page in firstPage until lastPage) {
            val text = try {
                documentLoader.readChapterText(file, EBookFormat.PDF, page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "Could not read PDF page $page for quotes.", error)
                ""
            }.trim()
            if (text.isBlank()) continue
            if (isNavigationText(text, page)) continue
            pages += capUnit(text)
            characters += pages.last().length
            if (characters >= CHUNK_TARGET_CHARS) break
        }
        return pages
    }

    /**
     * Selects a distinct, non-overlapping slice of the book for [chunkIndex].
     *
     * The old implementation skipped three chapters per chunk but stopped after
     * 800 characters, so chapters 1-2, 4-5, 7-8, ... were never sampled. This
     * version partitions all readable units proportionally, so a five-to-eight
     * chunk session walks the whole book. A single large chapter is sliced into
     * proportional windows, and cover/TOC/front-matter pages are ignored.
     */
    private fun sampleTextUnits(
        texts: Sequence<String>,
        chunkIndex: Int = 0,
        chunkCount: Int = 1,
    ): List<String> {
        val chunks = chunkCount.coerceAtLeast(1)
        val index = chunkIndex.coerceAtLeast(0)
        if (index >= chunks) return emptyList()

        val readable = texts
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterIndexed { unitIndex, text -> !isNavigationText(text, unitIndex) }
            .toList()
        if (readable.isEmpty()) return emptyList()

        // Fewer chapters than requested chunks: distribute several distinct
        // windows across each chapter instead of returning empty chunks or
        // repeating the same opening text.
        if (readable.size < chunks) {
            val windowsPerUnit = (chunks + readable.size - 1) / readable.size
            val unit = readable[index % readable.size]
            val windowIndex = index / readable.size
            return listOf(windowForChunk(unit, windowIndex, windowsPerUnit))
        }

        val start = (readable.size.toLong() * index / chunks).toInt()
            .coerceIn(0, readable.lastIndex)
        val end = (readable.size.toLong() * (index + 1) / chunks).toInt()
            .coerceIn(start + 1, readable.size)
        val bucket = readable.subList(start, end)
        if (bucket.size == 1) {
            return listOf(windowForChunk(bucket.first(), index, chunks))
        }

        // Distribute the target window across the bucket so one long chapter
        // cannot crowd out the others. Each slice is snapped to a sentence or
        // word boundary by the context sanitizer.
        val sliceChars = (CHUNK_TARGET_CHARS / bucket.size)
            .coerceIn(MIN_UNIT_SLICE_CHARS, CHUNK_TARGET_CHARS)
        return bucket.map { unit -> capUnit(unit, sliceChars) }
            .filter(String::isNotBlank)
    }

    /** Takes a proportional window from one oversized chapter. */
    private fun windowForChunk(text: String, chunkIndex: Int, chunkCount: Int): String {
        val clean = text.trim()
        if (clean.length <= CHUNK_TARGET_CHARS) return clean
        val safeChunks = chunkCount.coerceAtLeast(1)
        val start = (clean.length.toLong() * chunkIndex / safeChunks).toInt()
            .coerceIn(0, clean.lastIndex)
        // Prefer a paragraph/sentence boundary close to the calculated start.
        val boundary = sequenceOf(
            clean.indexOf("\n\n", start),
            clean.indexOf('.', start),
            clean.indexOf(' ', start),
        ).filter { it in start until minOf(start + BOUNDARY_SEARCH_CHARS, clean.length) }
            .minOrNull()
        val safeStart = boundary?.plus(1)?.coerceAtMost(clean.lastIndex) ?: start
        return capUnit(clean.substring(safeStart), CHUNK_TARGET_CHARS)
    }

    private fun capUnit(text: String, maxChars: Int = CHUNK_TARGET_CHARS): String {
        val clean = text.trim()
        if (clean.length <= maxChars) return clean
        return AiContextSanitizer.truncateAtBoundary(clean, maxChars).trim()
    }

    /**
     * Skips blank/scanned cover pages, copyright pages, and table of contents.
     * Short front matter is only ignored near the start of the document so a
     * genuinely short later chapter is never discarded.
     */
    private fun isNavigationText(text: String, unitIndex: Int): Boolean {
        val clean = text.trim()
        if (clean.length < MIN_READABLE_UNIT_CHARS && unitIndex < 3) return true
        val head = clean.take(NAVIGATION_HEAD_CHARS)
        if (NAVIGATION_HEADER.containsMatchIn(head)) return true
        // A text-layer table of contents is usually several dot-leader lines
        // ending in page numbers. Reject the whole page instead of sampling
        // chapter titles as if they were prose.
        val tocLines = clean.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(30)
            .count { line -> TOC_DOT_LEADER.containsMatchIn(line) }
        if (tocLines >= 3) return true
        if (unitIndex >= 3) return false
        val letters = clean.count(Char::isLetter)
        val digits = clean.count(Char::isDigit)
        return letters > 0 && letters <= digits
    }

    /**
     * Last-resort wallpaper material for books with no extractable text (for
     * example scanned/image-only PDFs). These reflective lines are built from
     * the book's own metadata and are only used after the AI returned no
     * usable quotes, so Wallpaper Studio never stays stuck at "0 new".
     */
    /**
     * Returns metadata quotes already accepted by a previous pass, or builds
     * thematic quote templates grounded in the book's title/author/contents.
     * Used only when the local text path cannot provide a usable quote, so a
     * scanned PDF never leaves Wallpaper Studio at zero.
     */
    private suspend fun fallbackQuotesFor(
        bookId: String,
        bookTitle: String,
        author: String?,
        chapter: String?,
        excluded: List<String>,
        count: Int,
        alreadyAccepted: List<String>,
        existingKeys: Set<String>,
    ): List<String> {
        val target = count.coerceIn(MIN_FALLBACK_QUOTES, MAX_FALLBACK_QUOTES)
        val accepted = alreadyAccepted
            .map(::normalizeQuote)
            .filter { it.length in MIN_QUOTE_CHARS..MAX_QUOTE_CHARS }
            .filterNot(AiResponseSanitizer::isArtifact)
            .filter(QuoteQualityValidator::isValidQuote)
            .filterNot { dedupeKey(it) in existingKeys }
            .distinctBy(::dedupeKey)
            .take(target)
        if (accepted.isNotEmpty()) return accepted

        return buildMetadataFallbackQuotes(
            bookId = bookId,
            bookTitle = bookTitle,
            author = author,
            chapter = chapter,
            excluded = excluded,
            count = target,
        )
            .map(::normalizeQuote)
            .filter { it.length in MIN_QUOTE_CHARS..MAX_QUOTE_CHARS }
            .filterNot(AiResponseSanitizer::isArtifact)
            .filter(QuoteQualityValidator::isValidQuote)
            .filterNot { dedupeKey(it) in existingKeys }
            .distinctBy(::dedupeKey)
            .take(target)
    }

    private suspend fun buildMetadataFallbackQuotes(
        bookId: String,
        bookTitle: String,
        author: String?,
        chapter: String?,
        excluded: List<String>,
        count: Int,
    ): List<String> {
        val title = AiContextSanitizer.oneLine(bookTitle)
            .take(MAX_FALLBACK_TITLE_CHARS)
            .ifBlank { "this book" }
        val authorText = author
            ?.let(AiContextSanitizer::oneLine)
            ?.take(MAX_FALLBACK_AUTHOR_CHARS)
            ?.takeIf(String::isNotBlank)
        val chapterText = chapter
            ?.let(AiContextSanitizer::oneLine)
            ?.take(MAX_FALLBACK_SECTION_CHARS)
            ?.takeIf(String::isNotBlank)
        val byAuthor = authorText?.let { " by $it" }.orEmpty()
        val contentsTitles = runCatching {
            database.bookChapterDao()
                .getForBook(bookId)
                .map { AiContextSanitizer.oneLine(it.title).take(MAX_FALLBACK_SECTION_CHARS) }
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_FALLBACK_CONTENTS_TITLES)
        }.getOrDefault(emptyList())
        val section = chapterText
            ?.let { "Chapter \"$it\" shows" }
            ?: "The book shows"
        val templates = buildList {
            add("\"$title\"$byAuthor invites readers to notice the quiet truths that shape everyday life.")
            add("Reading \"$title\"$byAuthor is an invitation to see ordinary moments with fresh attention.")
            add("\"$title\"$byAuthor reminds us that patience and reflection can transform the way we live.")
            add("The pages of \"$title\"$byAuthor reward careful readers with moments of clarity and recognition.")
            add("Every scene in \"$title\"$byAuthor asks us to consider the choices that give life meaning.")
            add("$section how small decisions can carry lasting meaning for the people we love.")
            add("A thoughtful reading of \"$title\"$byAuthor leaves room for both wonder and honest reflection.")
            add("\"$title\"$byAuthor offers a steady reminder that growth often begins in uncertainty.")
            // The contents list grounds otherwise synthetic text in the real
            // structure of a scanned/textless book.
            contentsTitles.forEach { sectionTitle ->
                add(
                    "The section \"$sectionTitle\" in \"$title\"$byAuthor invites " +
                        "readers to pause and reconsider what they value.",
                )
            }
        }
        val seen = excluded.mapTo(mutableSetOf(), ::dedupeKey)
        return templates
            .map(::normalizeQuote)
            .filter(QuoteQualityValidator::isValidQuote)
            .filter { seen.add(dedupeKey(it)) }
            .take(count.coerceIn(MIN_FALLBACK_QUOTES, MAX_FALLBACK_QUOTES))
    }

    private suspend fun persistResult(
        bookId: String,
        quotes: List<String>,
        chapter: String?,
        provider: AiProvider?,
        model: String?,
        onQuotePersisted: ((QuoteEntity) -> Unit)?,
        approveForWallpaper: Boolean,
    ): AiQuoteFetchResult {
        val inserted = persistQuotes(
            bookId = bookId,
            quotes = quotes,
            chapter = chapter,
            approveForWallpaper = approveForWallpaper,
            onQuotePersisted = onQuotePersisted,
        )
        Log.d(
            TAG,
            "Kept ${quotes.size} quote(s), inserted $inserted new " +
                "AI quote(s) for book=$bookId.",
        )
        return AiQuoteFetchResult(
            insertedCount = inserted,
            provider = provider,
            model = model,
            quotes = quotes,
        )
    }

    /**
     * Parses one provider response and throws when it contains no quote that
     * passes validation. [AiFailoverManager.completeValidated] catches this as
     * an attempt failure and advances to the next provider/key.
     */
    private fun extractUsableQuotes(
        raw: String,
        count: Int,
        bookId: String,
    ): List<String> {
        val parsed = AiQuoteResponseParser.parse(raw)
        val quotes = parsed
            .map(::normalizeQuote)
            .filter { it.length in MIN_QUOTE_CHARS..MAX_QUOTE_CHARS }
            .filterNot(AiResponseSanitizer::isArtifact)
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
                    "preview=${raw.take(PREVIEW_CHARS).replace('\n', ' ')}",
            )
            throw AiQuoteValidationException(
                "The AI response contained no usable quotes.",
            )
        }
        return quotes
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
        approveForWallpaper: Boolean,
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
            // Stop must interrupt a long batch immediately, even between rows.
            currentCoroutineContext().ensureActive()
            val text = normalizeQuote(raw)
            if (
                text.length !in MIN_QUOTE_CHARS..MAX_QUOTE_CHARS ||
                AiResponseSanitizer.isArtifact(text) ||
                !QuoteQualityValidator.isValidQuote(text) ||
                !existing.add(dedupeKey(text))
            ) {
                return@forEachIndexed
            }

            val entity = QuoteEntity(
                bookId = bookId,
                selectedText = text,
                chapter = safeChapter,
                isApprovedForWallpaper = approveForWallpaper,
                source = QuoteSource.AI,
                createdAt = createdAt + index,
            )
            val rowIds = database.quoteDao().insertAll(listOf(entity))
            val rowId = rowIds.firstOrNull() ?: -1L
            // Only positive row IDs represent a real insert. Room returns -1
            // for a conflict-ignored row, and the counter/carousel feedback
            // must never claim a card that does not exist in the database.
            if (rowId <= 0L) {
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

    private fun normalizeQuote(raw: String): String = AiResponseSanitizer
        .sanitize(raw, stripChatter = false)
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
        const val DEFAULT_BATCH_SIZE = 12
        const val REQUEST_TIMEOUT_MILLIS = 100_000L
        private const val AI_ERROR_TAG = "VibeReader_AI"
        private const val MAX_EXCLUSION_QUOTES = 20
        private const val MAX_EXCLUSION_CHARS = 70
        private const val MAX_FETCH_TOKENS = 2_500
        private const val MAX_QUOTE_CHARS = 260
        private const val MIN_QUOTE_CHARS = 20
        private const val MAX_CHAPTER_CHARS = 240
        private const val RETRIEVAL_SNIPPETS = 4
        private const val RETRIEVAL_CHARS = 2_500
        private const val CHUNK_TARGET_CHARS = 2_200
        private const val MIN_UNIT_SLICE_CHARS = 220
        private const val MIN_READABLE_UNIT_CHARS = 120
        private const val BOUNDARY_SEARCH_CHARS = 400
        private const val NAVIGATION_HEAD_CHARS = 360
        private const val PDF_PAGES_PER_CHUNK = 12
        private const val MIN_FALLBACK_QUOTES = 5
        private const val MAX_FALLBACK_QUOTES = 12
        private const val MAX_FALLBACK_CONTENTS_TITLES = 6
        private const val MAX_FALLBACK_TITLE_CHARS = 60
        private const val MAX_FALLBACK_AUTHOR_CHARS = 40
        private const val MAX_FALLBACK_SECTION_CHARS = 48
        private const val QUOTE_SOURCE_CACHE_DIR = "ai-quote-sources"
        private const val LOCAL_TEXT_TIMEOUT_MILLIS = 20_000L
        private const val PREVIEW_CHARS = 400
        private const val TERMINAL_STOP_WINDOW_MILLIS = 2_000L
        private const val TAG = "AiQuoteRepository"
        private val WHITESPACE = Regex("""\s+""")
        private val NAVIGATION_HEADER = Regex(
            "(?im)^\\s*(?:table of contents|contents|copyright|title page|cover)\\s*$",
        )
        private val TOC_DOT_LEADER = Regex(
            "(?i)^.{2,90}(?:\\.{3,}|…{2,}|·{3,})\\s*\\d{1,4}\\s*$",
        )
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
        // Strip thinking blocks, fences, and "Here are the quotes" chatter
        // before any JSON or line parsing. This is the last line of defence
        // behind the provider client's sanitizer.
        val cleaned = AiResponseSanitizer.sanitize(raw, extractFencedPayload = true)
        if (cleaned.isBlank()) return emptyList()

        // Preferred path: a valid JSON array. Objects may use any of the known
        // quote field names, and nested wrappers are tolerated.
        val root = extractJsonArray(cleaned)
        val jsonQuotes = extractQuotes(root)
            .filterNot(::isLeakText)
            .filterNot(AiResponseSanitizer::isArtifact)
        if (jsonQuotes.isNotEmpty()) return jsonQuotes

        // Some models produce a nearly-valid array (trailing comma, prose or a
        // fence inside the payload). Recover explicit quote fields first.
        val fieldQuotes = extractQuoteFields(cleaned)
            .filterNot(::isLeakText)
            .filterNot(AiResponseSanitizer::isArtifact)
        if (fieldQuotes.isNotEmpty()) return fieldQuotes

        // Last resort: one quote per line, with optional numbering/bullets.
        return parseLines(cleaned)
            .filterNot(::isLeakText)
            .filterNot(AiResponseSanitizer::isArtifact)
    }

    /** True when text looks like model reasoning rather than a book quote. */
    fun isLeakText(text: String): Boolean =
        LEAK_MARKERS.any { marker -> text.contains(marker, ignoreCase = true) }

    /** Tries every JSON-array start so prose before the payload is tolerated. */
    private fun extractJsonArray(text: String): List<*>? {
        for (start in text.indices) {
            if (text[start] != '[') continue
            val value = runCatching { JsonScanner(text, start).parseValue() }.getOrNull()
            if (value is List<*>) return value
        }
        return null
    }

    private fun extractQuotes(root: Any?): List<String> = when (root) {
        is List<*> -> root.mapNotNull(::stringFromItem)
        else -> emptyList()
    }

    /**
     * Recovers `"quote": "..."` / `text: "..."` pairs from responses whose JSON
     * is slightly malformed (for example a trailing comma). This keeps valid
     * quotes from being dropped by a single syntax slip.
     */
    private fun extractQuoteFields(text: String): List<String> =
        QUOTE_FIELD_REGEX.findAll(text)
            .map { match -> cleanItem(match.groupValues[1]) }
            .filter(String::isNotEmpty)
            .toList()

    /**
     * Line-by-line fallback for numbered lists, bullets, and plain lines. JSON
     * punctuation and field names are stripped so common formatting variations
     * still yield usable quotes.
     */
    private fun parseLines(text: String): List<String> = text.lineSequence()
        .mapNotNull { rawLine ->
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("```")) return@mapNotNull null
            line = LIST_PREFIX.replace(line, "")
            line = QUOTE_KEY_PREFIX.replace(line, "")
            line = cleanItem(line)
            if (line.isEmpty()) return@mapNotNull null
            if (line.startsWith("{") || line.startsWith("}")) return@mapNotNull null
            if (line.startsWith("[") || line.startsWith("]")) return@mapNotNull null
            if (line.startsWith("#") || line.endsWith(":")) return@mapNotNull null
            // Require at least three words; the quality validator applies the
            // final quote-length and punctuation rules later.
            if (line.count { it.isWhitespace() } < 2) return@mapNotNull null
            line
        }
        .toList()

    private fun stringFromItem(item: Any?): String? = when (item) {
        is String -> cleanItem(item).takeIf(String::isNotEmpty)
        is Map<*, *> -> STRING_FIELDS.firstNotNullOfOrNull { field ->
            (item[field] as? String)
                ?.let(::cleanItem)
                ?.takeIf(String::isNotEmpty)
        }

        else -> null
    }

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
                    ',' -> {
                        position++
                        skipWhitespace()
                        // Tolerate a trailing comma before the closing bracket,
                        // a common free-model JSON defect.
                        if (peek() == ']') {
                            position++
                            return values
                        }
                    }

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
                    ',' -> {
                        position++
                        skipWhitespace()
                        if (peek() == '}') {
                            position++
                            return values
                        }
                    }

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

    private val STRING_FIELDS = listOf("quote", "text", "content", "selectedText", "excerpt")
    private val LIST_PREFIX = Regex("""^\s*(?:[-*•]\s*)?(?:\d+[.)]\s*)?""")
    private val QUOTE_KEY_PREFIX = Regex(
        """^(?:quote|text|content|selectedText|excerpt)\s*[:=-]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val QUOTE_FIELD_REGEX = Regex(
        "(?i)(?:quote|text|content|selectedText|excerpt)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
    )
    private val LEAK_MARKERS = listOf(
        "UNKNOWN_BOOK",
        "JSON array",
        "User asks:",
        "We need",
        "Task:",
        "Instructions",
        "Not sure",
        "appears in the book",
        "thinking process",
        "<think",
        "</think",
        "<thought",
        "[/THOUGHT]",
        "reasoning_content",
        "as an AI",
        "here are the quotes",
        "let me analyze",
        "the user wants",
        "i need to find",
        "we need to find",
        "let's look",
        "looking at the passage",
        "i should extract",
    )
    private val WHITESPACE = Regex("""\s+""")
}

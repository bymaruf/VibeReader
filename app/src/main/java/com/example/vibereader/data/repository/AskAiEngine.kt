package com.example.vibereader.data.repository

import android.content.Context
import android.util.Log
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.domain.prompt.AiContextSanitizer
import com.example.vibereader.domain.prompt.AiPromptTemplates
import com.example.vibereader.domain.prompt.BookQuestionScope
import com.example.vibereader.domain.prompt.LocalKeywordRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ask AI / Book Assistant engine for the Reader.
 *
 * Token strategy:
 * - General book questions first ask the model using only the title and author
 *   ("zero-context" mode). The model must answer from built-in knowledge or
 *   return [AiPromptTemplates.UNKNOWN_BOOK].
 * - Once a provider reports an unknown book, or the question explicitly points
 *   at the current passage, the engine scores local paragraphs with BM25 and
 *   sends only the top four (~2,500 characters total).
 * - Answers are capped at 400 output tokens and cached in Room by book, request
 *   kind, and a hash of the question/retrieved context.
 *
 * [AiFailoverManager] still resolves the full provider/key pool for every
 * network request; this class only changes what is sent and caches successful
 * responses.
 */
class AskAiEngine(
    context: Context,
    private val keyVault: AiKeyVault = AiKeyVault(context.applicationContext),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build(),
    private val responseCache: AiResponseCache = AiResponseCache(
        VibeReaderDatabase.getInstance(context.applicationContext),
    ),
) {
    data class BookContext(
        val bookTitle: String,
        val author: String?,
        /** Selection, visible page, or current chapter text. */
        val contextText: String,
        val bookId: String = "",
        /** Full EPUB chapters when available; PDFs pass an empty list. */
        val bookChapters: List<String> = emptyList(),
        /** True when [contextText] is a selection or a single page. */
        val contextIsExcerpt: Boolean = false,
    )

    enum class Task(val title: String) {
        SUMMARIZE("Summary"),
        EXPLAIN("Explanation"),
        EXTRACT_QUOTE("Quote extraction"),
    }

    /** Centralized, thread-safe active AI config snapshot (IO-safe callers). */
    fun getActiveAiConfig(): ActiveAiConfig = AiRuntimeResolver.getActiveAiConfig(keyVault)

    /** Diagnostic snapshot of every provider that can participate in failover. */
    fun resolvedConfigs(): List<ResolvedAiRequest> = AiRuntimeResolver.resolve(keyVault)

    /**
     * Answers a free-form question about the current book with the smallest
     * prompt that can produce a reliable answer.
     */
    suspend fun askBook(
        context: BookContext,
        question: String,
    ): String = withContext(Dispatchers.IO) {
        executeAi("Ask AI") {
            val trimmedQuestion = question.trim()
            if (trimmedQuestion.isBlank()) {
                throw IOException("Type a question before asking the AI.")
            }
            val bookTitle = context.bookTitle.trim().ifBlank { "this book" }
            val author = context.author?.trim()?.takeIf(String::isNotBlank)
            val bookId = context.bookId.trim()
            val metadataKey = AiResponseCache.hash(
                "ask-metadata",
                bookTitle,
                author.orEmpty(),
                trimmedQuestion,
            )
            if (bookId.isNotBlank()) {
                responseCache.get(bookId, AiResponseCache.KIND_ASK, metadataKey)
                    ?.usableAiTextOrNull()
                    ?.let {
                        Log.d(TAG, "Ask AI metadata answer served from Room cache.")
                        return@executeAi it
                    }
            }

            val passageSpecific = BookQuestionScope.requiresPassageContext(trimmedQuestion)
            var metadataFailure: Throwable? = null
            if (!passageSpecific) {
                try {
                    val metadataAnswer = askFromBuiltInKnowledge(
                        bookTitle = bookTitle,
                        author = author,
                        question = trimmedQuestion,
                    )
                    if (metadataAnswer != null) {
                        if (bookId.isNotBlank()) {
                            responseCache.put(
                                bookId = bookId,
                                cacheKind = AiResponseCache.KIND_ASK,
                                queryHash = metadataKey,
                                content = metadataAnswer,
                            )
                        }
                        return@executeAi metadataAnswer
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // No key/model or an exhausted pool must not block a
                    // previously cached RAG answer. Remember the failure and
                    // only rethrow it if local retrieval has no cached answer.
                    if (error !is AiConfigurationException && error !is AiRequestException) {
                        throw error
                    }
                    metadataFailure = error
                }
            }

            val snippets = resolveSnippets(context, trimmedQuestion)
            if (snippets.isEmpty()) {
                metadataFailure?.let { throw it }
                throw IOException(
                    if (passageSpecific) {
                        "I couldn't find the selected passage. Try selecting the text again."
                    } else {
                        "I couldn't find enough text in this book to answer that."
                    },
                )
            }
            val passageBlock = snippets.joinToString("\n\n")
            val ragKey = AiResponseCache.hash(
                "ask-rag",
                bookTitle,
                author.orEmpty(),
                trimmedQuestion,
                passageBlock,
            )
            if (bookId.isNotBlank()) {
                responseCache.get(bookId, AiResponseCache.KIND_ASK, ragKey)
                    ?.usableAiTextOrNull()
                    ?.let {
                        Log.d(TAG, "Ask AI RAG answer served from Room cache.")
                        return@executeAi it
                    }
            }
            metadataFailure?.let { throw it }

            logFailoverPool()
            val systemPrompt = AiPromptTemplates.buildBookRagSystemPrompt(
                bookTitle = bookTitle,
                author = author,
                passages = passageBlock,
            )
            val completion = AiFailoverManager.complete(
                keyVault = keyVault,
                httpClient = httpClient,
                systemPrompt = systemPrompt,
                userContent = "Question: $trimmedQuestion",
                maxTokens = MAX_ANSWER_TOKENS,
                temperature = 0.3,
                logTag = TAG,
                overallTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
            )
            val answer = completion.text.usableAiTextOrNull()
                ?: throw IOException("The AI provider returned an empty answer. Try again.")
            if (answer.isNotBlank() && bookId.isNotBlank()) {
                responseCache.put(
                    bookId = bookId,
                    cacheKind = AiResponseCache.KIND_ASK,
                    queryHash = ragKey,
                    content = answer,
                )
            }
            Log.d(
                TAG,
                "Ask AI answered via ${completion.provider.shortLabel} " +
                    "model=${completion.model} with ${passageBlock.length} context chars.",
            )
            answer
        }
    }

    /**
     * Summarizes the whole book from the model's online knowledge. Only the
     * title and author leave the device; no chapters, pages, or excerpts are
     * included. Unknown books become [AiPromptTemplates.UNKNOWN_BOOK_MESSAGE]
     * so the reader card can show a clean message instead of the sentinel.
     */
    suspend fun summarizeEntireBook(
        bookTitle: String,
        author: String?,
        bookId: String,
    ): String = withContext(Dispatchers.IO) {
        executeAi("Book summary") {
            val safeTitle = bookTitle.trim().ifBlank { "this book" }
            val safeAuthor = author?.trim()?.takeIf(String::isNotBlank)
            val safeBookId = bookId.trim()
            val cacheKey = AiResponseCache.hash(
                "entire-book-summary",
                safeTitle,
                safeAuthor.orEmpty(),
            )
            if (safeBookId.isNotBlank()) {
                responseCache.get(safeBookId, AiResponseCache.KIND_READER_TASK, cacheKey)
                    ?.usableAiTextOrNull()
                    ?.let {
                        Log.d(TAG, "Entire-book summary served from Room cache.")
                        return@executeAi it
                    }
            }

            logFailoverPool()
            val completion = AiFailoverManager.complete(
                keyVault = keyVault,
                httpClient = httpClient,
                systemPrompt = AiPromptTemplates.buildEntireBookSummarySystemPrompt(
                    bookTitle = safeTitle,
                    author = safeAuthor,
                ),
                userContent = "Summarize this book.",
                maxTokens = MAX_SUMMARY_TOKENS,
                temperature = 0.3,
                logTag = TAG,
                overallTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
            )
            val answer = completion.text.usableAiTextOrNull()
                ?: throw IOException("The AI provider returned an empty book summary. Try again.")
            if (AiPromptTemplates.isUnknownBookResponse(answer)) {
                Log.d(
                    TAG,
                    "Model reported UNKNOWN_BOOK for entire-book summary of '$safeTitle'.",
                )
                return@executeAi AiPromptTemplates.UNKNOWN_BOOK_MESSAGE
            }
            if (safeBookId.isNotBlank()) {
                responseCache.put(
                    bookId = safeBookId,
                    cacheKind = AiResponseCache.KIND_READER_TASK,
                    queryHash = cacheKey,
                    content = answer,
                )
            }
            Log.d(
                TAG,
                "Entire-book summary answered via ${completion.provider.shortLabel} " +
                    "model=${completion.model} without sending book text.",
            )
            answer
        }
    }

    /** Runs one of the reader's structured AI tasks over [text]. */
    suspend fun runTask(task: Task, text: String): String = withContext(Dispatchers.IO) {
        executeAi(task.title) {
            val input = AiContextSanitizer.truncateAtBoundary(
                AiContextSanitizer.sanitize(text),
                MAX_TASK_INPUT_CHARS,
            )
            if (input.isBlank()) {
                throw IOException("There is no readable text to send to the AI assistant.")
            }
            val systemPrompt = when (task) {
                Task.SUMMARIZE ->
                    "Summarize the supplied book passage as a thoughtful reading companion. " +
                        "Highlight key emotional and logical takeaways in concise, natural " +
                        "paragraphs. Do not use generic introductions or invent details."

                Task.EXPLAIN ->
                    "Explain the supplied book excerpt clearly and faithfully for a reader. " +
                        "Use plain language, stay close to the text, and do not invent details."

                Task.EXTRACT_QUOTE ->
                    "Extract one concise, faithful quote from the supplied text. Return only " +
                        "valid JSON with exactly two string fields: \"header\" (one uppercase " +
                        "word) and \"quote\" (the meaningful excerpt). Do not add facts or " +
                        "commentary."
            }
            val completion = AiFailoverManager.complete(
                keyVault = keyVault,
                httpClient = httpClient,
                systemPrompt = systemPrompt,
                userContent = input,
                maxTokens = when (task) {
                    Task.SUMMARIZE -> MAX_SUMMARY_TOKENS
                    Task.EXPLAIN -> MAX_EXPLAIN_TOKENS
                    Task.EXTRACT_QUOTE -> MAX_QUOTE_TOKENS
                },
                temperature = when (task) {
                    Task.SUMMARIZE -> 0.4
                    Task.EXPLAIN -> 0.3
                    Task.EXTRACT_QUOTE -> 0.1
                },
                logTag = TAG,
                overallTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
            )
            Log.d(
                TAG,
                "${task.title} answered via ${completion.provider.shortLabel} " +
                    "model=${completion.model} (${completion.text.length} chars).",
            )
            completion.text.usableAiTextOrNull()
                ?: throw IOException(
                    "The AI provider returned an empty ${task.title.lowercase()}. Try again.",
                )
        }
    }

    /**
     * Zero-context attempt. Returns null when the model does not recognize the
     * book and the caller should fall back to local retrieval.
     */
    private suspend fun askFromBuiltInKnowledge(
        bookTitle: String,
        author: String?,
        question: String,
    ): String? {
        val completion = AiFailoverManager.complete(
            keyVault = keyVault,
            httpClient = httpClient,
            systemPrompt = AiPromptTemplates.buildBookMetadataSystemPrompt(bookTitle, author),
            userContent = "Question: $question",
            maxTokens = MAX_ANSWER_TOKENS,
            temperature = 0.3,
            logTag = TAG,
            overallTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
        )
        val answer = completion.text.usableAiTextOrNull()
        if (answer == null || AiPromptTemplates.isUnknownBookResponse(answer)) {
            Log.d(TAG, "Model reported UNKNOWN_BOOK for '$bookTitle'; using local retrieval.")
            return null
        }
        Log.d(
            TAG,
            "Ask AI answered zero-context via ${completion.provider.shortLabel} " +
                "model=${completion.model}.",
        )
        return answer
    }

    /**
     * Chooses the smallest useful local context:
     * - a selection/page is already the exact passage, so send only that;
     * - a chapter/full book is scored locally and the top four chunks are sent.
     */
    private fun resolveSnippets(context: BookContext, question: String): List<String> {
        val excerpt = AiContextSanitizer.sanitize(context.contextText)
        if (context.contextIsExcerpt && excerpt.isNotBlank()) {
            return listOf(
                AiContextSanitizer.sanitizeSnippet(excerpt, MAX_RETRIEVAL_CHARS),
            ).filter(String::isNotBlank)
        }

        val chapters = context.bookChapters
            .map(AiContextSanitizer::sanitize)
            .filter(String::isNotBlank)
        val priority = excerpt.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        val sources = chapters.ifEmpty { priority }
        if (sources.isEmpty()) return emptyList()
        return LocalKeywordRetriever.selectRelevantSnippets(
            query = question,
            sources = sources,
            prioritySources = priority,
            maxSnippets = MAX_RETRIEVAL_SNIPPETS,
            maxChars = MAX_RETRIEVAL_CHARS,
        )
    }

    /**
     * Resolves the selected provider plus every enabled failover provider before
     * any prompt or network work. If no provider has both a key and a saved
     * model, [AiRuntimeResolver] throws the exact setup message.
     */
    private fun logFailoverPool(): List<ResolvedAiRequest> {
        val configs = AiRuntimeResolver.resolve(keyVault)
        Log.d(
            TAG,
            "Failover pool (${configs.size}): " +
                configs.joinToString { "${it.provider.shortLabel}:${it.model}" },
        )
        return configs
    }

    /**
     * Catches every [Throwable] so an AI action can never fail silently. The
     * error is logged with the required `VibeReader_AI` tag and rethrown as a
     * user-facing exception for the Reader UI.
     */
    private fun String.usableAiTextOrNull(): String? = trim().takeIf {
        it.isNotEmpty() &&
            !it.equals("null", ignoreCase = true) &&
            !it.equals("undefined", ignoreCase = true)
    }

    private suspend fun <T> executeAi(
        label: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.e(AI_ERROR_TAG, "Execution error", error)
        throw error.toAssistantException(label)
    }

    private fun Throwable.toAssistantException(label: String): Throwable = when (this) {
        is AiConfigurationException,
        is AiRequestException,
        -> this

        else -> AiRequestException(
            message = "AI Error: " + (
                localizedMessage?.takeIf(String::isNotBlank)
                    ?: message?.takeIf(String::isNotBlank)
                    ?: "$label failed with ${javaClass.simpleName}."
                ),
            cause = this,
        )
    }

    private companion object {
        const val TAG = "AskAiEngine"
        const val AI_ERROR_TAG = "VibeReader_AI"
        const val REQUEST_TIMEOUT_MILLIS = 90_000L
        const val MAX_TASK_INPUT_CHARS = 12_000
        const val MAX_ANSWER_TOKENS = 1024
        const val MAX_SUMMARY_TOKENS = 1024
        const val MAX_EXPLAIN_TOKENS = 1024
        const val MAX_QUOTE_TOKENS = 400
        const val MAX_RETRIEVAL_SNIPPETS = 4
        const val MAX_RETRIEVAL_CHARS = 2_500
    }
}

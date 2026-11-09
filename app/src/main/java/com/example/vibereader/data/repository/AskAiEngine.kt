package com.example.vibereader.data.repository

import android.content.Context
import android.util.Log
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.domain.prompt.AiPromptTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ask AI / Book Assistant engine for the Reader (Summarize, Explain, Extract
 * Quote, and Ask Book AI).
 *
 * Every request resolves the user's full failover pool on each invocation: the
 * selected provider comes first, followed by every other enabled provider that
 * has both an API key and the exact model saved for that provider in DataStore
 * (`SELECTED_OPENROUTER_MODEL`, `SELECTED_GEMINI_MODEL`,
 * `SELECTED_OPENCODE_MODEL`, and so on).
 *
 * [AiFailoverManager] automatically retries the next provider/key when a
 * provider returns HTTP 401/403/404/429, any other HTTP error, a network
 * exception, or an unexpected runtime error. Each failover attempt dynamically
 * uses the model saved for the provider being tried, so switching providers
 * never reuses another provider's model ID.
 *
 * A missing key/model fails immediately with
 * [AiRuntimeResolver.SETUP_ERROR_MESSAGE]. After every key in the pool is
 * exhausted, [AiFailoverManager.ALL_KEYS_FAILED_MESSAGE] is surfaced. All
 * failures are logged with the `VibeReader_AI` tag so they are never silent.
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
) {
    data class BookContext(
        val bookTitle: String,
        val author: String?,
        val contextText: String,
    )

    enum class Task(val title: String) {
        SUMMARIZE("Summary"),
        EXPLAIN("Explanation"),
        EXTRACT_QUOTE("Quote extraction"),
    }

    /** Diagnostic snapshot of every provider that can participate in failover. */
    fun resolvedConfigs(): List<ResolvedAiRequest> = AiRuntimeResolver.resolve(keyVault)

    /**
     * Answers a free-form question about the current book. [BookContext]
     * contains the title, author, and the exact reading context supplied by the
     * reader (selection, current page, or current chapter).
     */
    suspend fun askBook(
        context: BookContext,
        question: String,
    ): String = withContext(Dispatchers.IO) {
        executeAi("Ask AI") {
            logFailoverPool()

            val trimmedQuestion = question.trim()
            if (trimmedQuestion.isBlank()) {
                throw IOException("Type a question before asking the AI.")
            }
            val safeContext = context.contextText
                .trim()
                .take(MAX_CONTEXT_CHARS)
                .takeIf(String::isNotBlank)
                ?: throw IOException("Open a page or select text before asking the AI.")

            val systemPrompt = AiPromptTemplates.buildBookQaPrompt(
                bookTitle = context.bookTitle.trim().ifBlank { "this book" },
                author = context.author,
                contextSnippet = safeContext,
                userQuestion = trimmedQuestion,
            )
            val completion = AiFailoverManager.complete(
                keyVault = keyVault,
                httpClient = httpClient,
                systemPrompt = systemPrompt,
                userContent = "Reader question: $trimmedQuestion",
                maxTokens = MAX_ANSWER_TOKENS,
                temperature = 0.4,
                logTag = TAG,
                overallTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
            )
            Log.d(
                TAG,
                "Ask AI answered via ${completion.provider.shortLabel} " +
                    "model=${completion.model} (${completion.text.length} chars).",
            )
            completion.text
        }
    }

    /** Runs one of the reader's structured AI tasks over [text]. */
    suspend fun runTask(task: Task, text: String): String = withContext(Dispatchers.IO) {
        executeAi(task.title) {
            logFailoverPool()

            val input = text.trim()
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
            completion.text
        }
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
     * user-facing exception for ReaderViewModel's error card, Snackbar, and
     * Toast.
     */
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
        const val MAX_CONTEXT_CHARS = 12_000
        const val MAX_ANSWER_TOKENS = 768
        const val MAX_SUMMARY_TOKENS = 512
        const val MAX_EXPLAIN_TOKENS = 768
        const val MAX_QUOTE_TOKENS = 256
    }
}

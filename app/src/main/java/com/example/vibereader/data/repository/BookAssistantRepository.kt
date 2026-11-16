package com.example.vibereader.data.repository

import android.content.Context
import com.example.vibereader.data.local.AiKeyVault
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Reader-facing Book Assistant facade.
 *
 * The actual prompt shaping, local BM25 retrieval, output caps, and Room cache
 * live in [AskAiEngine] so every book-AI entry point uses the same token budget.
 * This class keeps the historical public API for any caller that still targets
 * [BookAssistantRepository] directly.
 */
class BookAssistantRepository(
    context: Context,
    private val keyVault: AiKeyVault = AiKeyVault(context.applicationContext),
    httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build(),
) {
    private val engine = AskAiEngine(context, keyVault, httpClient)

    data class BookContext(
        val bookTitle: String,
        val author: String?,
        val contextText: String,
        val bookId: String = "",
        val bookChapters: List<String> = emptyList(),
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
    fun resolvedConfigs(): List<ResolvedAiRequest> = engine.resolvedConfigs()

    suspend fun askBook(
        context: BookContext,
        question: String,
    ): String = engine.askBook(
        context = AskAiEngine.BookContext(
            bookTitle = context.bookTitle,
            author = context.author,
            contextText = context.contextText,
            bookId = context.bookId,
            bookChapters = context.bookChapters,
            contextIsExcerpt = context.contextIsExcerpt,
        ),
        question = question,
    )

    suspend fun runTask(task: Task, text: String): String =
        engine.runTask(task.toEngineTask(), text)

    private fun Task.toEngineTask(): AskAiEngine.Task = when (this) {
        Task.SUMMARIZE -> AskAiEngine.Task.SUMMARIZE
        Task.EXPLAIN -> AskAiEngine.Task.EXPLAIN
        Task.EXTRACT_QUOTE -> AskAiEngine.Task.EXTRACT_QUOTE
    }
}

package com.example.vibereader.domain

import android.content.Context
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.data.repository.AiFailoverManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single entry point for every reader AI action.
 *
 * Text requests are delegated to [AiFailoverManager], which owns the rotating
 * provider/key pool, 60-second HTTP 429 cooldowns, and session-wide 401/403
 * blacklisting. Keeping that state in one process-wide object means requests
 * from the reader, quotes screen, and wallpaper curator all share the same key
 * health instead of each maintaining an independent failover loop.
 */
class AiManager(
    context: Context,
    private val keyVault: AiKeyVault = AiKeyVault(context.applicationContext),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    data class AiRequest(
        val task: AiTask,
        val text: String,
    )

    enum class AiTask(val title: String) {
        SUMMARIZE("Summary"),
        EXPLAIN("Explanation"),
        EXTRACT_QUOTE("Quote extraction"),
    }

    /** Convenience overload for the typed reader tasks. */
    suspend fun executeWithFallback(request: AiRequest): String {
        val systemPrompt = when (request.task) {
            AiTask.SUMMARIZE ->
                com.example.vibereader.domain.prompt.AiPromptTemplates
                    .buildHumanSummaryPrompt(request.text)
            AiTask.EXPLAIN ->
                "Explain the supplied book excerpt clearly and faithfully. Do not invent details."
            AiTask.EXTRACT_QUOTE ->
                "Extract one concise, faithful quote from the supplied text. Return only valid JSON " +
                    "with exactly two string fields: \"header\" (one uppercase word) and \"quote\" " +
                    "(the meaningful excerpt). Do not add facts or commentary."
        }
        val maxTokens = when (request.task) {
            AiTask.SUMMARIZE -> 512
            AiTask.EXPLAIN -> 768
            AiTask.EXTRACT_QUOTE -> 256
        }
        val temperature = when (request.task) {
            AiTask.SUMMARIZE -> 0.4
            AiTask.EXPLAIN -> 0.3
            AiTask.EXTRACT_QUOTE -> 0.1
        }
        return executeWithFailover(
            systemPrompt = systemPrompt,
            userContent = request.text,
            maxTokens = maxTokens,
            temperature = temperature,
        )
    }

    /**
     * Runs [systemPrompt]/[userContent] against the shared rotating pool until
     * one key succeeds. Used by "Ask this book" and other free-form calls.
     */
    suspend fun executeWithFailover(
        systemPrompt: String,
        userContent: String,
        maxTokens: Int? = null,
        temperature: Double = 0.3,
    ): String = AiFailoverManager.complete(
        keyVault = keyVault,
        httpClient = httpClient,
        systemPrompt = systemPrompt,
        userContent = userContent,
        maxTokens = maxTokens,
        temperature = temperature,
        logTag = TAG,
    ).text

    companion object {
        private const val TAG = "AiFailover"
    }
}

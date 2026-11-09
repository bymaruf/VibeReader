package com.example.vibereader.domain

import android.content.Context
import android.util.Log
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.data.remote.AiProviderClient
import com.example.vibereader.model.AiProvider
import com.example.vibereader.model.ProviderKeyConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Single entry point for every reader AI action.
 *
 * The manager implements the seamless API fallback loop: all configured
 * providers are tried in order (the selected one first), and any failure —
 * HTTP error, invalid key, timeout, malformed response, or unexpected
 * runtime error — silently routes the request to the next provider. A UI
 * error is only surfaced once every saved provider has been exhausted.
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
     * Runs [systemPrompt]/[userContent] against every configured provider
     * until one succeeds. Used by "Ask this book" and other free-form calls.
     */
    suspend fun executeWithFailover(
        systemPrompt: String,
        userContent: String,
        maxTokens: Int? = null,
        temperature: Double = 0.3,
    ): String = executeWithFailover { provider, apiKey, model ->
        AiProviderClient.executeChat(
            client = httpClient,
            provider = provider,
            apiKey = apiKey,
            model = model,
            systemPrompt = systemPrompt,
            userContent = userContent,
            maxTokens = maxTokens,
            temperature = temperature,
        )
    }

    /**
     * Core failover loop.
     *
     * Every provider is attempted exactly once. [CancellationException] is
     * rethrown so coroutine cancellation stays cooperative; everything else
     * is logged and skipped. Only after the final provider fails is the last
     * error wrapped in a user-facing [IOException].
     */
    private suspend fun <T> executeWithFailover(
        attempt: suspend (provider: AiProvider, apiKey: String, model: String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val configs = resolveProviderConfigs()
        if (configs.isEmpty()) {
            throw IOException(
                "No AI provider is configured. Add an API key in Settings.",
            )
        }

        var lastError: Exception? = null
        val attempted = mutableListOf<String>()
        configs.forEachIndexed { index, config ->
            attempted += config.provider.shortLabel
            val model = keyVault.getAiModel(config.provider)
                ?.takeIf(String::isNotBlank)
                ?: config.provider.defaultModel
            try {
                return@withContext attempt(config.provider, config.apiKey, model)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error as? Exception
                    ?: IOException(
                        "${config.provider.displayName} failed: ${error.localizedMessage}",
                        error,
                    )
                Log.w(
                    TAG,
                    "AI provider ${config.provider.displayName} failed " +
                        "(${index + 1}/${configs.size}); trying next.",
                    error,
                )
                // continue -> instantly try the next saved API key/provider
            }
        }

        val detail = lastError?.localizedMessage?.takeIf(String::isNotBlank)
        throw IOException(
            "All ${configs.size} AI providers failed (${attempted.joinToString()})." +
                (detail?.let { " Last error: $it" } ?: "") +
                " Check your API keys or internet connection.",
            lastError,
        )
    }

    /**
     * Selected provider first, then every other enabled provider with a key.
     * Configs without a key or explicitly disabled are never attempted.
     */
    private fun resolveProviderConfigs(): List<ProviderKeyConfig> =
        keyVault.getEnabledProviderConfigs(keyVault.getSelectedProvider())
            .filter { it.isEnabled && it.apiKey.isNotBlank() }
            .distinctBy(ProviderKeyConfig::provider)

    companion object {
        private const val TAG = "AiFailover"
    }
}

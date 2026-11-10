package com.example.vibereader.data.repository

import android.util.Log
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.data.remote.AiProviderClient
import com.example.vibereader.data.remote.AiProviderHttpException
import com.example.vibereader.model.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * One fully resolved provider request.
 *
 * [model] is never null or blank. It is always the exact value persisted under
 * the provider's dedicated DataStore key (for example
 * `SELECTED_OPENROUTER_MODEL`, `SELECTED_GEMINI_MODEL` or
 * `SELECTED_OPENCODE_MODEL`). A missing key or model is skipped instead of
 * being sent to a provider with an empty model payload.
 */
data class ResolvedAiRequest(
    val provider: AiProvider,
    val apiKey: String,
    val model: String,
    val modelSource: AiModelSource,
    val modelPreferenceKey: String,
)

/**
 * Centralized, validated AI configuration snapshot for the currently selected
 * active provider. Both [apiKey] and [modelId] are guaranteed non-blank when an
 * instance is returned by [AiRuntimeResolver.getActiveAiConfig].
 */
data class ActiveAiConfig(
    val provider: AiProvider,
    val apiKey: String,
    val modelId: String,
    val modelPreferenceKey: String,
)

enum class AiModelSource {
    SAVED,

    /** Kept for source compatibility. Normal resolution now always uses [SAVED]. */
    PROVIDER_DEFAULT,
}

/** Raised before any network call when no provider/key/model setup is usable. */
class AiConfigurationException(message: String) : IOException(message)

/** User-facing failure after every configured provider was attempted. */
class AiRequestException(
    message: String,
    val provider: AiProvider? = null,
    val model: String? = null,
    val statusCode: Int? = null,
    /**
     * True when this request reached the end of the whole failover pool
     * without a success. Callers such as the endless quote fetcher can use this
     * to stop their background loop instead of retrying immediately.
     */
    val allKeysExhausted: Boolean = false,
    /**
     * Terminal failure for the current background operation. Always true for
     * [allKeysExhausted]; also true for timeouts and unusable responses where
     * an immediate automatic retry would simply repeat the same failure.
     */
    val stopBackgroundFetch: Boolean = allKeysExhausted,
    cause: Throwable? = null,
) : IOException(message, cause)

data class AiCompletion(
    val text: String,
    val provider: AiProvider,
    val model: String,
)

/**
 * A provider response that passed caller-supplied validation. [value] is the
 * parsed result (for example, a list of usable quotes) produced by the
 * validator that accepted this attempt.
 */
data class AiValidatedCompletion<T>(
    val value: T,
    val provider: AiProvider,
    val model: String,
)

/**
 * Resolves every usable entry in the user's API-key/failover pool.
 *
 * The selected provider is always first. OpenRouter, OpenCode, Gemini and
 * Anthropic (Claude) follow in that order, then any remaining enabled
 * providers. Each provider's model is read dynamically from its own DataStore
 * key at resolution time, so a failover to Gemini uses
 * `SELECTED_GEMINI_MODEL`, a failover to OpenCode uses
 * `SELECTED_OPENCODE_MODEL`, and so on.
 *
 * A provider with a missing key or a missing saved model is logged and skipped;
 * it is never called with a blank model. If no provider has both values, the
 * request fails immediately with [SETUP_ERROR_MESSAGE] before any network call.
 */
object AiRuntimeResolver {
    /**
     * Shown whenever no usable provider/key/model configuration exists. The
     * error is raised before any network request is attempted.
     */
    const val SETUP_ERROR_MESSAGE =
        "Please enter API Key and select a Model in Settings."

    private const val TAG = "AiRuntimeResolver"
    private const val AI_ERROR_TAG = "VibeReader_AI"

    /**
     * Non-active provider order for every AI feature. The active provider is
     * always tried first; if it fails, the sweep continues through OpenRouter,
     * OpenCode, Gemini and Claude before the remaining providers.
     */
    private val FAILOVER_PRIORITY = listOf(
        AiProvider.OPENROUTER,
        AiProvider.OPENCODE,
        AiProvider.GEMINI,
        AiProvider.ANTHROPIC,
        AiProvider.OPENAI,
        AiProvider.GROQ,
        AiProvider.DEEPSEEK,
    )

    fun resolve(keyVault: AiKeyVault): List<ResolvedAiRequest> {
        val activeProvider = keyVault.getSelectedProvider()
        val resolved = linkedMapOf<AiProvider, ResolvedAiRequest>()

        fun tryAdd(provider: AiProvider, isActive: Boolean) {
            val config = keyVault.getProviderConfig(provider)
            val apiKey = config.apiKey.trim()
            val model = keyVault.getAiModel(provider)
                ?.trim()
                .orEmpty()
            val enabled = isActive || config.isEnabled

            if (!enabled) {
                Log.d(TAG, "Skipping ${provider.shortLabel}: provider is disabled.")
                return
            }
            if (apiKey.isBlank() || model.isBlank()) {
                Log.w(
                    TAG,
                    "Skipping ${provider.shortLabel}: " +
                        "keyPresent=${apiKey.isNotBlank()}, " +
                        "modelPresent=${model.isNotBlank()} " +
                        "(expected ${modelPreferenceKey(provider)}).",
                )
                return
            }

            resolved[provider] = ResolvedAiRequest(
                provider = provider,
                apiKey = apiKey,
                model = model,
                modelSource = AiModelSource.SAVED,
                modelPreferenceKey = modelPreferenceKey(provider),
            )
            Log.d(
                TAG,
                "Resolved ${provider.shortLabel}: key=${modelPreferenceKey(provider)}, " +
                    "model=$model, active=${provider == activeProvider}",
            )
        }

        tryAdd(activeProvider, isActive = true)
        FAILOVER_PRIORITY
            .asSequence()
            .filter { it != activeProvider }
            .forEach { provider -> tryAdd(provider, isActive = false) }
        AiProvider.entries
            .asSequence()
            .filter { it != activeProvider && it !in FAILOVER_PRIORITY }
            .forEach { provider -> tryAdd(provider, isActive = false) }

        if (resolved.isEmpty()) {
            val error = AiConfigurationException(SETUP_ERROR_MESSAGE)
            Log.e(AI_ERROR_TAG, "Execution error", error)
            throw error
        }
        return resolved.values.toList()
    }

    fun resolveActive(keyVault: AiKeyVault): ResolvedAiRequest = resolve(keyVault).first()

    /**
     * Centralized active-provider resolver. It inspects the currently selected
     * AI provider, dynamically retrieves that provider's API key and dedicated
     * model ID (`SELECTED_OPENROUTER_MODEL`, `SELECTED_GEMINI_MODEL`,
     * `SELECTED_OPENCODE_MODEL`, etc.), and validates that neither is blank.
     *
     * If the active provider is missing key/model, it still returns the first
     * usable resolved failover entry (so AI features can proceed when another
     * saved provider is configured). If no provider in the pool has both a key
     * and a saved model, it throws [AiConfigurationException] with
     * [SETUP_ERROR_MESSAGE] before any network call.
     *
     * Thread-safety note: [AiKeyVault] reads are synchronous/shared-preference
     * backed; callers should invoke this from [Dispatchers.IO] when used in UI
     * flows. Repositories in this codebase already run AI work on IO.
     */
    fun getActiveAiConfig(keyVault: AiKeyVault): ActiveAiConfig {
        val resolved = resolveActive(keyVault)
        return ActiveAiConfig(
            provider = resolved.provider,
            apiKey = resolved.apiKey,
            modelId = resolved.model,
            modelPreferenceKey = resolved.modelPreferenceKey,
        )
    }

    /**
     * Strict active-provider validation (no failover fallback): requires the
     * selected provider itself to have both API key and model configured.
     * Throws [SETUP_ERROR_MESSAGE] immediately when missing/blank.
     */
    fun requireActiveProviderConfig(keyVault: AiKeyVault): ActiveAiConfig {
        val provider = keyVault.getSelectedProvider()
        val apiKey = keyVault.getProviderConfig(provider).apiKey.trim()
        val modelId = keyVault.getAiModel(provider)?.trim().orEmpty()
        if (apiKey.isBlank() || modelId.isBlank()) {
            val error = AiConfigurationException(SETUP_ERROR_MESSAGE)
            Log.e(AI_ERROR_TAG, "Execution error", error)
            throw error
        }
        return ActiveAiConfig(
            provider = provider,
            apiKey = apiKey,
            modelId = modelId,
            modelPreferenceKey = modelPreferenceKey(provider),
        )
    }

    /**
     * Mirrors the exact keys used by [com.example.vibereader.data.local.AiModelPreferences].
     * Keeping the names here makes provider/model diagnostics unambiguous.
     */
    fun modelPreferenceKey(provider: AiProvider): String = when (provider) {
        AiProvider.OPENROUTER -> "SELECTED_OPENROUTER_MODEL"
        AiProvider.OPENCODE -> "SELECTED_OPENCODE_MODEL"
        AiProvider.GEMINI -> "SELECTED_GEMINI_MODEL"
        AiProvider.OPENAI -> "SELECTED_OPENAI_MODEL"
        AiProvider.ANTHROPIC -> "SELECTED_ANTHROPIC_MODEL"
        AiProvider.GROQ -> "SELECTED_GROQ_MODEL"
        AiProvider.DEEPSEEK -> "SELECTED_DEEPSEEK_MODEL"
    }
}

/**
 * Automatic API-key/provider failover for both the Book Assistant and the
 * wallpaper quote fetcher.
 *
 * Every request resolves the full provider pool on each invocation, starts with
 * the selected provider, and immediately retries the next provider on HTTP
 * 401/403/404/429, any other HTTP failure, a network exception, a timeout, or
 * an unexpected runtime error. [completeValidated] additionally treats a
 * response that fails caller-side validation (for example, no usable quotes)
 * as an attempt failure and advances to the next provider. The model used for
 * each attempt is the exact model saved for that provider, so switching
 * providers never reuses another provider's model ID.
 *
 * The UI only receives a failure after every key/provider in the pool has been
 * exhausted. A failed sweep is terminal: this manager throws one
 * [AiRequestException] and never retries the pool internally, so callers can
 * stop their background loop instead of hammering the same failing keys.
 * A single hard timeout prevents the "Working…" state from hanging when
 * several providers each take a long time to fail.
 */
object AiFailoverManager {
    private const val TAG = "AiFailoverManager"
    private const val AI_ERROR_TAG = "VibeReader_AI"

    /**
     * A single upper bound for the whole operation, including failover. Without
     * this, two or more provider timeouts could make the UI look permanently
     * stuck on "Working…".
     */
    const val DEFAULT_OVERALL_TIMEOUT_MILLIS = 90_000L

    /** Exact user-facing message when every saved key/provider has failed. */
    const val ALL_KEYS_FAILED_MESSAGE = "All API keys failed or out of quota."

    suspend fun complete(
        keyVault: AiKeyVault,
        httpClient: OkHttpClient,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int?,
        temperature: Double,
        logTag: String = TAG,
        overallTimeoutMillis: Long = DEFAULT_OVERALL_TIMEOUT_MILLIS,
    ): AiCompletion {
        val result = completeValidated(
            keyVault = keyVault,
            httpClient = httpClient,
            systemPrompt = systemPrompt,
            userContent = userContent,
            maxTokens = maxTokens,
            temperature = temperature,
            logTag = logTag,
            overallTimeoutMillis = overallTimeoutMillis,
            validate = { text -> text },
        )
        return AiCompletion(
            text = result.value,
            provider = result.provider,
            model = result.model,
        )
    }

    /**
     * Runs the same sequential provider/key sweep as [complete], but every
     * response must pass [validate] before it is accepted. A parsing failure,
     * empty result, or any other validation exception counts as an attempt
     * failure, so the next provider/key in the sequence is tried immediately.
     *
     * This is what lets quote fetching fail over when a provider returns HTTP
     * 200 with unusable text instead of stopping at the first provider.
     */
    suspend fun <T> completeValidated(
        keyVault: AiKeyVault,
        httpClient: OkHttpClient,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int?,
        temperature: Double,
        logTag: String = TAG,
        overallTimeoutMillis: Long = DEFAULT_OVERALL_TIMEOUT_MILLIS,
        validate: (String) -> T,
    ): AiValidatedCompletion<T> = withContext(Dispatchers.IO) {
        val configs = try {
            AiRuntimeResolver.resolve(keyVault)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(AI_ERROR_TAG, "Execution error", error)
            throw error
        }
        // Hard ceiling for one request attempt. The app stores one key per
        // provider (seven providers today), so this allows the full pool while
        // capping runaway growth if more providers/keys are added later.
        val attempts = configs.take(MAX_FAILOVER_ATTEMPTS)

        Log.d(
            TAG,
            "Failover pool (${attempts.size}): " +
                attempts.joinToString { "${it.provider.shortLabel}:${it.model}" },
        )

        val boundedTimeout = overallTimeoutMillis.coerceAtLeast(1_000L)
        val completion = withTimeoutOrNull(boundedTimeout) {
            var lastError: Throwable? = null

            for ((index, config) in attempts.withIndex()) {
                // Stop immediately if the caller cancelled while a previous
                // provider was failing; never start another request after a
                // cancelled/finished fetch session.
                currentCoroutineContext().ensureActive()
                try {
                    val (text, usedModel) = executeProvider(
                        httpClient = httpClient,
                        config = config,
                        systemPrompt = systemPrompt,
                        userContent = userContent,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        logTag = logTag,
                    )

                    // Validation failures are attempt failures, not terminal
                    // request failures. Advance to the next provider/key.
                    val value = try {
                        validate(text)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (validationError: Throwable) {
                        lastError = validationError
                        Log.e(AI_ERROR_TAG, "Execution error", validationError)
                        Log.e(
                            logTag,
                            "AI response validation failed on " +
                                "${config.provider.displayName} " +
                                "(model=$usedModel) [${index + 1}/${attempts.size}]; " +
                                "trying next provider.",
                            validationError,
                        )
                        currentCoroutineContext().ensureActive()
                        continue
                    }

                    Log.d(
                        TAG,
                        "Provider ${config.provider.shortLabel} succeeded with " +
                            "model=$usedModel [${index + 1}/${attempts.size}].",
                    )
                    return@withTimeoutOrNull AiValidatedCompletion(
                        value = value,
                        provider = config.provider,
                        model = usedModel,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastError = error
                    // Exact required trace for every failed attempt. The next
                    // provider/key in the pool is tried immediately after this.
                    Log.e(AI_ERROR_TAG, "Execution error", error)
                    Log.e(
                        logTag,
                        "AI request failed on ${config.provider.displayName} " +
                            "(model=${config.model}, key=${config.modelPreferenceKey}) " +
                            "[${index + 1}/${attempts.size}]; trying next provider.",
                        error,
                    )
                    // Honour cancellation before advancing to the next key.
                    currentCoroutineContext().ensureActive()
                }
            }

            throw buildExhaustedException(attempts, lastError)
        }

        completion ?: run {
            val timeoutError = AiRequestException(
                message = "The AI request timed out. Check your internet connection " +
                    "and try again.",
                stopBackgroundFetch = true,
            )
            Log.e(AI_ERROR_TAG, "Execution error", timeoutError)
            Log.e(
                logTag,
                "AI request exceeded the overall timeout of ${boundedTimeout}ms " +
                    "after attempting ${attempts.size} provider(s).",
            )
            throw timeoutError
        }
    }

    private suspend fun executeProvider(
        httpClient: OkHttpClient,
        config: ResolvedAiRequest,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int?,
        temperature: Double,
        logTag: String,
    ): Pair<String, String> {
        try {
            val text = AiProviderClient.executeChat(
                client = httpClient,
                provider = config.provider,
                apiKey = config.apiKey,
                model = config.model,
                systemPrompt = systemPrompt,
                userContent = userContent,
                maxTokens = maxTokens,
                temperature = temperature,
            )
            return text to config.model
        } catch (error: AiProviderHttpException) {
            val defaultModel = config.provider.defaultModel.trim()
            val savedModelRejected = defaultModel.isNotBlank() &&
                !defaultModel.equals(config.model, ignoreCase = true) &&
                (
                    error.statusCode == 404 ||
                        (
                            error.statusCode == 400 &&
                                error.message.orEmpty().contains("model", ignoreCase = true)
                            )
                    )
            if (!savedModelRejected) throw error

            Log.w(
                logTag,
                "Saved model '${config.model}' was rejected by " +
                    "${config.provider.displayName} (HTTP ${error.statusCode}). " +
                    "Retrying the provider default '$defaultModel'.",
                error,
            )
            val text = AiProviderClient.executeChat(
                client = httpClient,
                provider = config.provider,
                apiKey = config.apiKey,
                model = defaultModel,
                systemPrompt = systemPrompt,
                userContent = userContent,
                maxTokens = maxTokens,
                temperature = temperature,
            )
            return text to defaultModel
        }
    }

    /**
     * Every configured provider/key was attempted. The UI gets one stable,
     * explicit message; provider/model/HTTP details stay in Logcat because the
     * user cannot act on a per-gateway error once the whole pool is exhausted.
     */
    private fun buildExhaustedException(
        configs: List<ResolvedAiRequest>,
        lastError: Throwable?,
    ): AiRequestException {
        val httpError = lastError as? AiProviderHttpException
        if (lastError != null) {
            Log.e(
                TAG,
                "All ${configs.size} provider/key attempt(s) failed: " +
                    configs.joinToString { it.provider.shortLabel } +
                    ". Last error: ${lastError.localizedMessage ?: lastError.message}",
                lastError,
            )
        } else {
            Log.e(
                TAG,
                "All ${configs.size} provider/key attempt(s) failed without a captured error.",
            )
        }
        return AiRequestException(
            message = ALL_KEYS_FAILED_MESSAGE,
            provider = configs.lastOrNull()?.provider,
            model = configs.lastOrNull()?.model,
            statusCode = httpError?.statusCode,
            allKeysExhausted = true,
            cause = lastError,
        )
    }

    private const val MAX_FAILOVER_ATTEMPTS = 10
}

package com.example.vibereader.data.repository

import android.util.Log
import com.example.vibereader.BuildConfig
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.data.remote.AiProviderClient
import com.example.vibereader.data.remote.AiProviderHttpException
import com.example.vibereader.data.remote.OpenCodeModelPolicy
import com.example.vibereader.model.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
 * Gemini keys supplied at build time through the git-ignored
 * `local.properties` file:
 *
 * ```
 * GEMINI_API_KEYS=key-one,key-two,key-three
 * ```
 *
 * Gradle turns the comma-separated value into `BuildConfig.GEMINI_API_KEYS`.
 * Whitespace and surrounding quotes are stripped here as a second line of
 * defence, and placeholders/duplicates are removed. The list is never null;
 * an unconfigured build simply exposes an empty pool.
 */
internal object GeminiBuildTimeKeys {
    private const val PLACEHOLDER = "your_key_here"

    val keys: List<String> = BuildConfig.GEMINI_API_KEYS
        .orEmpty()
        .asSequence()
        .map { it.trim().trim('"', '\'').trim() }
        .filter { it.isNotEmpty() && it != PLACEHOLDER }
        .distinct()
        .toList()
}

/** Stable in-memory identity used by cooldown/blacklist maps. Never logged. */
private fun ResolvedAiRequest.keyIdentity(): String = "${provider.name}:$apiKey"

/** Non-reversible-enough diagnostic label for cooldown logs. Never logs the key. */
private fun ResolvedAiRequest.keyFingerprint(): String =
    "${provider.name}:#${apiKey.hashCode().toUInt().toString(16)}"

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
 * Gemini can contribute more than one key: the saved key (or legacy
 * `GEMINI_API_KEY`) plus every entry in the build-time `GEMINI_API_KEYS`
 * array. Duplicate key strings are removed. When build-time keys exist but no
 * Gemini model has been saved yet, the provider default model is used so the
 * pool works on a fresh install. A provider with no key or no usable model is
 * logged and skipped; it is never called with a blank model. If no
 * provider/key has both values, the request fails immediately with
 * [SETUP_ERROR_MESSAGE] before any network call.
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
        val resolved = mutableListOf<ResolvedAiRequest>()
        val seenKeys = mutableSetOf<String>()

        fun tryAdd(provider: AiProvider, isActive: Boolean) {
            val config = keyVault.getProviderConfig(provider)
            val buildTimeKeys =
                if (provider == AiProvider.GEMINI) GeminiBuildTimeKeys.keys else emptyList()
            val savedModel = AiProviderClient.normalizeModelId(
                provider,
                keyVault.getAiModel(provider).orEmpty(),
            )
            // CLI/client-restricted OpenCode models must never reach the REST
            // endpoint, even if an older build persisted one as the active
            // model. Fall back to the provider's REST-safe default instead.
            // A build-time Gemini pool (including the legacy single-key
            // fallback) must also work without a Settings visit. Providers
            // without build-time keys still require an explicitly saved model.
            val model = when {
                provider == AiProvider.OPENCODE &&
                    OpenCodeModelPolicy.isRestrictedModel(savedModel) -> {
                    Log.w(
                        TAG,
                        "Saved OpenCode model '$savedModel' is CLI/client-restricted; " +
                            "using '${provider.defaultModel}' for REST inference.",
                    )
                    AiProviderClient.normalizeModelId(provider, provider.defaultModel)
                        .takeUnless { OpenCodeModelPolicy.isRestrictedModel(it) }
                        .orEmpty()
                }

                savedModel.isNotBlank() -> savedModel

                buildTimeKeys.isNotEmpty() ->
                    AiProviderClient.normalizeModelId(provider, provider.defaultModel)

                else -> ""
            }
            val enabled = isActive || config.isEnabled

            if (!enabled) {
                Log.d(TAG, "Skipping ${provider.shortLabel}: provider is disabled.")
                return
            }
            if (model.isBlank()) {
                Log.w(
                    TAG,
                    "Skipping ${provider.shortLabel}: model is not selected " +
                        "(expected ${modelPreferenceKey(provider)}).",
                )
                return
            }

            // The saved key wins for the single-key slot. Gemini additionally
            // contributes every build-time key from local.properties.
            val keys = buildList {
                add(config.apiKey)
                addAll(buildTimeKeys)
            }
                .asSequence()
                .map { it.trim().trim('"', '\'').trim() }
                .filter(String::isNotBlank)
                .distinct()
                .toList()

            if (keys.isEmpty()) {
                Log.w(
                    TAG,
                    "Skipping ${provider.shortLabel}: no API key is configured.",
                )
                return
            }

            keys.forEach { apiKey ->
                val request = ResolvedAiRequest(
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    modelSource = AiModelSource.SAVED,
                    modelPreferenceKey = modelPreferenceKey(provider),
                )
                if (seenKeys.add(request.keyIdentity())) {
                    resolved += request
                }
            }
            Log.d(
                TAG,
                "Resolved ${provider.shortLabel}: keys=${keys.size}, model=$model, " +
                    "active=${provider == activeProvider}",
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
        return resolved
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
 * Automatic API-key/provider failover for every AI path in the app.
 *
 * Key rotation and health:
 * - Every request resolves the full provider/key pool, then starts at the next
 *   entry selected by an [AtomicInteger] round-robin cursor. With Gemini's
 *   build-time pool this gives `key 0 -> key 1 -> key 2 -> key 0` ordering
 *   instead of hammering the first key.
 * - HTTP 429 places only that key on a 60-second [keyCooldowns] entry and the
 *   request immediately advances to the next key. Cooldowns are purged
 *   automatically when they expire, so no app restart is required.
 * - HTTP 401/403 permanently disables that key for the process session
 *   ([disabledKeys]); it is never selected again until the app restarts.
 * - Timeouts ([SocketTimeoutException] / [InterruptedIOException]) mark the
 *   stalled provider as temporarily busy and fail over immediately to the next
 *   provider/key. Other network IO failures and 5xx responses get up to two
 *   exponential backoff retries on the same key before the sweep moves on.
 *   Validation failures (for example an unusable quote payload) advance
 *   immediately.
 * - If every remaining key is on a 429 cooldown, the manager waits for the
 *   earliest expiry and retries rather than surfacing an "all keys failed"
 *   error. [ALL_KEYS_FAILED_MESSAGE] is reserved for a pool where every key is
 *   disabled, has failed, or the device is offline.
 *
 * [completeValidated] treats a response that fails caller-side validation as
 * an attempt failure. The model used for each attempt is the saved model for
 * that provider (or its REST-safe default), so switching providers never
 * reuses another provider's model ID. A single hard timeout prevents the
 * "Working…" state from hanging.
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

    /** 429 keys rest for this long before rejoining the rotation. */
    private const val RATE_LIMIT_COOLDOWN_MILLIS = 60_000L

    /** 5xx/network failures are retried this many times on the same key. */
    private const val MAX_TRANSIENT_RETRIES = 2

    private const val BASE_BACKOFF_MILLIS = 500L

    /** A timed-out provider is deprioritized for this long, then restored. */
    private const val TIMEOUT_BUSY_MILLIS = 30_000L

    /**
     * Key identity -> epoch millis at which the key becomes usable again.
     * Entries are removed automatically once their cooldown expires.
     */
    private val keyCooldowns = ConcurrentHashMap<String, Long>()

    /**
     * Provider name -> epoch millis until which its requests are considered
     * stalled. Timed-out providers move to the end of the failover order
     * temporarily so healthy providers are preferred, then rejoin automatically.
     */
    private val providerBusyUntil = ConcurrentHashMap<String, Long>()

    /** Keys disabled for the whole app session after HTTP 401/403. */
    private val disabledKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Last failure observed for each key. A single key that is skipped while
     * cooling down or disabled still reports its exact HTTP/timeout reason on
     * the next request instead of falling back to the generic quota message.
     */
    private val keyFailureReasons = ConcurrentHashMap<String, Throwable>()

    /** Rotates the starting key for every new request. */
    private val roundRobinIndex = AtomicInteger(0)

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
     * Runs the same rotating key/provider sweep as [complete], but every
     * response must pass [validate] before it is accepted. A parsing failure,
     * empty result, or any other validation exception counts as an attempt
     * failure, so the next key/provider in the sequence is tried immediately.
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
        // Hard ceiling for one request attempt. The pool normally contains one
        // key per provider plus the Gemini build-time rotation keys.
        val attempts = configs.take(MAX_FAILOVER_ATTEMPTS)

        Log.d(
            TAG,
            "Failover pool (${attempts.size} key(s)): " +
                attempts.joinToString { "${it.provider.shortLabel}:${it.model}" },
        )

        val boundedTimeout = overallTimeoutMillis.coerceAtLeast(1_000L)
        val completion = withTimeoutOrNull(boundedTimeout) {
            executeFailoverSweep(
                attempts = attempts,
                httpClient = httpClient,
                systemPrompt = systemPrompt,
                userContent = userContent,
                maxTokens = maxTokens,
                temperature = temperature,
                logTag = logTag,
                validate = validate,
            )
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
                    "with ${attempts.size} key(s) in the pool.",
            )
            throw timeoutError
        }
    }

    /**
     * Sweeps the pool in round-robin order. Healthy keys are attempted once per
     * request; 429 keys are parked for [RATE_LIMIT_COOLDOWN_MILLIS] and may be
     * retried once their cooldown expires. 401/403 keys are disabled for the
     * session. Transient failures are retried on the same key before advancing.
     */
    private suspend fun <T> executeFailoverSweep(
        attempts: List<ResolvedAiRequest>,
        httpClient: OkHttpClient,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int?,
        temperature: Double,
        logTag: String,
        validate: (String) -> T,
    ): AiValidatedCompletion<T> {
        var lastError: Throwable? = null
        val triedKeys = mutableSetOf<String>()
        val rateLimitedKeys = mutableSetOf<String>()
        val timedOutProviders = mutableSetOf<AiProvider>()

        while (true) {
            // Cooldowns that expired since the previous pass are restored to
            // the active pool before the next selection.
            purgeExpiredCooldowns()
            purgeExpiredProviderBusy()
            // Recently timed-out providers are still tried if every healthy
            // provider fails, but they no longer lead the fallback order.
            val ordered = roundRobinOrder(attempts)
                .sortedBy { if (isProviderTemporarilyBusy(it.provider)) 1 else 0 }

            for ((index, config) in ordered.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (config.provider in timedOutProviders) continue
                val identity = config.keyIdentity()
                if (identity in disabledKeys) continue
                if (isCoolingDown(identity)) continue
                if (identity in triedKeys && identity !in rateLimitedKeys) continue

                // A key that just came off cooldown is allowed one fresh pass.
                rateLimitedKeys.remove(identity)
                triedKeys += identity

                try {
                    val (text, usedModel) = executeProviderWithRetries(
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
                        keyFailureReasons[identity] = validationError
                        Log.e(AI_ERROR_TAG, "Execution error", validationError)
                        Log.e(
                            logTag,
                            "AI response validation failed on " +
                                "${config.provider.displayName} " +
                                "(model=$usedModel) [${index + 1}/${attempts.size}]; " +
                                "trying next key.",
                            validationError,
                        )
                        currentCoroutineContext().ensureActive()
                        continue
                    }

                    Log.d(
                        TAG,
                        "Key ${config.keyFingerprint()} succeeded with " +
                            "provider=${config.provider.shortLabel}, model=$usedModel " +
                            "[${index + 1}/${attempts.size}].",
                    )
                    keyFailureReasons.remove(identity)
                    return AiValidatedCompletion(
                        value = value,
                        provider = config.provider,
                        model = usedModel,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (timeout: SocketTimeoutException) {
                    lastError = timeout
                    keyFailureReasons[identity] = timeout
                    markProviderTemporarilyBusy(config.provider)
                    timedOutProviders += config.provider
                    Log.e(AI_ERROR_TAG, "Execution error", timeout)
                    Log.w(
                        logTag,
                        "Timeout on ${config.provider.displayName} " +
                            "(model=${config.model}, key=${config.keyFingerprint()}) " +
                            "[${index + 1}/${attempts.size}]; marked temporarily busy " +
                            "for ${TIMEOUT_BUSY_MILLIS}ms and failing over to the next " +
                            "provider/key.",
                        timeout,
                    )
                    currentCoroutineContext().ensureActive()
                } catch (timeout: InterruptedIOException) {
                    lastError = timeout
                    keyFailureReasons[identity] = timeout
                    markProviderTemporarilyBusy(config.provider)
                    timedOutProviders += config.provider
                    Log.e(AI_ERROR_TAG, "Execution error", timeout)
                    Log.w(
                        logTag,
                        "Network interruption/timeout on ${config.provider.displayName} " +
                            "(model=${config.model}, key=${config.keyFingerprint()}) " +
                            "[${index + 1}/${attempts.size}]; marked temporarily busy " +
                            "for ${TIMEOUT_BUSY_MILLIS}ms and failing over to the next " +
                            "provider/key.",
                        timeout,
                    )
                    currentCoroutineContext().ensureActive()
                } catch (error: AiProviderHttpException) {
                    lastError = error
                    keyFailureReasons[identity] = error
                    when (error.statusCode) {
                        429 -> {
                            keyCooldowns[identity] = System.currentTimeMillis() +
                                RATE_LIMIT_COOLDOWN_MILLIS
                            rateLimitedKeys += identity
                            Log.w(
                                logTag,
                                "Rate limit (HTTP 429) on ${config.keyFingerprint()}; " +
                                    "cooling down for ${RATE_LIMIT_COOLDOWN_MILLIS}ms " +
                                    "and trying the next key.",
                            )
                        }

                        401, 403 -> {
                            disabledKeys += identity
                            Log.w(
                                logTag,
                                "Authentication failure (HTTP ${error.statusCode}) on " +
                                    "${config.keyFingerprint()}; key disabled for this " +
                                    "app session.",
                            )
                        }

                        else -> Log.e(
                            logTag,
                            "AI request failed on ${config.provider.displayName} " +
                                "(model=${config.model}, key=${config.keyFingerprint()}) " +
                                "[${index + 1}/${attempts.size}]; trying next key.",
                            error,
                        )
                    }
                    Log.e(AI_ERROR_TAG, "Execution error", error)
                    // Honour cancellation before advancing to the next key.
                    currentCoroutineContext().ensureActive()
                } catch (error: Throwable) {
                    lastError = error
                    keyFailureReasons[identity] = error
                    Log.e(AI_ERROR_TAG, "Execution error", error)
                    Log.e(
                        logTag,
                        "AI request failed on ${config.provider.displayName} " +
                            "(model=${config.model}, key=${config.keyFingerprint()}) " +
                            "[${index + 1}/${attempts.size}]; trying next key.",
                        error,
                    )
                    currentCoroutineContext().ensureActive()
                }
            }

            // Every currently usable key was tried. If some keys are merely
            // rate-limited, wait for the earliest cooldown instead of reporting
            // a terminal "all keys failed" error.
            val now = System.currentTimeMillis()
            purgeExpiredCooldowns()
            val earliestCooldown = attempts
                .asSequence()
                .filter { it.keyIdentity() !in disabledKeys }
                .mapNotNull { keyCooldowns[it.keyIdentity()] }
                .filter { it > now }
                .minOrNull()
            if (earliestCooldown != null) {
                if (attempts.size == 1) {
                    // A single key cannot rotate anywhere else. Surface its
                    // exact HTTP error immediately instead of hiding it behind
                    // a 60-second cooldown and then a generic quota message.
                    throw buildExhaustedException(
                        attempts,
                        lastError ?: rememberedFailure(attempts),
                    )
                }
                val waitMillis = (earliestCooldown - now)
                    .coerceAtMost(RATE_LIMIT_COOLDOWN_MILLIS)
                Log.w(
                    TAG,
                    "All usable keys are rate-limited; waiting ${waitMillis}ms " +
                        "for the earliest cooldown to expire.",
                )
                delay(waitMillis)
                currentCoroutineContext().ensureActive()
                continue
            }

            throw buildExhaustedException(
                attempts,
                lastError ?: rememberedFailure(attempts),
            )
        }
    }

    /**
     * Most recent remembered failure for a single-key pool. Multi-key pools
     * intentionally get no remembered fallback so an old status cannot be
     * misreported after a different key becomes terminal.
     */
    private fun rememberedFailure(attempts: List<ResolvedAiRequest>): Throwable? {
        if (attempts.size != 1) return null
        return attempts.firstNotNullOfOrNull { keyFailureReasons[it.keyIdentity()] }
    }

    /**
     * Circularly rotates the pool so the next request starts at the next key.
     * The rotation is process-wide and lock-free.
     */
    private fun roundRobinOrder(
        attempts: List<ResolvedAiRequest>,
    ): List<ResolvedAiRequest> {
        if (attempts.size <= 1) return attempts
        val start = Math.floorMod(roundRobinIndex.getAndIncrement(), attempts.size)
        if (start == 0) return attempts
        return buildList(attempts.size) {
            addAll(attempts.subList(start, attempts.size))
            addAll(attempts.subList(0, start))
        }
    }

    /** Removes expired cooldowns so those keys rejoin the active pool. */
    private fun purgeExpiredCooldowns() {
        val now = System.currentTimeMillis()
        for ((key, until) in keyCooldowns) {
            if (now >= until) {
                keyCooldowns.remove(key, until)
            }
        }
    }

    private fun isCoolingDown(key: String): Boolean {
        val until = keyCooldowns[key] ?: return false
        if (System.currentTimeMillis() >= until) {
            keyCooldowns.remove(key, until)
            return false
        }
        return true
    }

    private fun markProviderTemporarilyBusy(provider: AiProvider) {
        providerBusyUntil[provider.name] = System.currentTimeMillis() + TIMEOUT_BUSY_MILLIS
    }

    private fun isProviderTemporarilyBusy(provider: AiProvider): Boolean {
        val until = providerBusyUntil[provider.name] ?: return false
        if (System.currentTimeMillis() >= until) {
            providerBusyUntil.remove(provider.name, until)
            return false
        }
        return true
    }

    /** Restores providers whose timeout-busy window has elapsed. */
    private fun purgeExpiredProviderBusy() {
        val now = System.currentTimeMillis()
        for ((provider, until) in providerBusyUntil) {
            if (now >= until) {
                providerBusyUntil.remove(provider, until)
            }
        }
    }

    /**
     * Retries 5xx and non-timeout network failures with 500ms/1000ms
     * exponential backoff. Timeouts are rethrown immediately so the sweep can
     * mark the provider busy and fail over without stalling the request. HTTP
     * 4xx responses are classified by the caller so a 429 can immediately move
     * to the next key and a 401/403 can be blacklisted.
     */
    private suspend fun executeProviderWithRetries(
        httpClient: OkHttpClient,
        config: ResolvedAiRequest,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int?,
        temperature: Double,
        logTag: String,
    ): Pair<String, String> {
        var retry = 0
        while (true) {
            try {
                return executeProvider(
                    httpClient = httpClient,
                    config = config,
                    systemPrompt = systemPrompt,
                    userContent = userContent,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    logTag = logTag,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (timeout: SocketTimeoutException) {
                // Fail over immediately; retrying a stalled provider only
                // delays switching to the next key/provider.
                throw timeout
            } catch (timeout: InterruptedIOException) {
                throw timeout
            } catch (error: Throwable) {
                if (!error.isTransientRetryable() || retry >= MAX_TRANSIENT_RETRIES) {
                    throw error
                }
                val delayMillis = BASE_BACKOFF_MILLIS * (1L shl retry)
                Log.w(
                    logTag,
                    "Transient failure on ${config.provider.displayName} " +
                        "(model=${config.model}, key=${config.keyFingerprint()}); " +
                        "retrying in ${delayMillis}ms " +
                        "(${retry + 1}/$MAX_TRANSIENT_RETRIES).",
                    error,
                )
                delay(delayMillis)
                retry++
            }
        }
    }

    private fun Throwable.isTransientRetryable(): Boolean {
        val httpError = this as? AiProviderHttpException
        if (httpError != null) return httpError.statusCode in 500..599
        return this is IOException
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
            val defaultModel = AiProviderClient.normalizeModelId(
                config.provider,
                config.provider.defaultModel,
            )
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
     * Every configured provider/key was attempted. A single-key pool gets the
     * exact HTTP status/server text or timeout reason so Settings testing and
     * one-key users can act on it. Multi-key pools keep one stable message;
     * per-gateway details remain in Logcat.
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

        val singleConfig = configs.singleOrNull()
        val message = when {
            singleConfig == null -> ALL_KEYS_FAILED_MESSAGE

            httpError != null -> formatSingleKeyHttpError(httpError)

            lastError is SocketTimeoutException ||
                lastError is InterruptedIOException ->
                formatSingleKeyTimeout(singleConfig, lastError)

            lastError != null -> "AI request failed for " +
                "${singleConfig.provider.displayName}: " +
                (
                    lastError.localizedMessage
                        ?.takeIf(String::isNotBlank)
                        ?: lastError.message?.takeIf(String::isNotBlank)
                        ?: lastError.javaClass.simpleName
                    )

            else -> ALL_KEYS_FAILED_MESSAGE
        }

        return AiRequestException(
            message = message,
            provider = (singleConfig ?: configs.lastOrNull())?.provider,
            model = (singleConfig ?: configs.lastOrNull())?.model,
            statusCode = httpError?.statusCode,
            allKeysExhausted = true,
            cause = lastError,
        )
    }

    private fun formatSingleKeyHttpError(error: AiProviderHttpException): String {
        val detail = error.serverMessage
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (detail != null) return "HTTP ${error.statusCode}: $detail"

        // The full exception message is built as
        // "<provider> returned HTTP <code>: <body>". Reuse the body when the
        // provider did not return parseable JSON.
        val fallback = error.message
            .orEmpty()
            .substringAfter("HTTP ${error.statusCode}:", "")
            .trim()
            .takeIf(String::isNotBlank)
        return if (fallback != null) {
            "HTTP ${error.statusCode}: $fallback"
        } else {
            "HTTP ${error.statusCode}: request failed."
        }
    }

    private fun formatSingleKeyTimeout(
        config: ResolvedAiRequest,
        error: Throwable,
    ): String {
        if (
            config.provider == AiProvider.OPENCODE &&
            OpenCodeModelPolicy.isFreeCliModel(config.model)
        ) {
            return "Timeout: Selected model '${config.model}' is restricted to the " +
                "OpenCode CLI and may time out in the mobile app. Choose an " +
                "unrestricted model in Settings."
        }
        val reason = error.message?.trim()?.takeIf(String::isNotBlank)
        return buildString {
            append("Timeout: ")
            append(config.provider.displayName)
            append(" did not respond")
            if (reason != null) {
                append(" (")
                append(reason)
                append(')')
            }
            append(". Model: ")
            append(config.model)
            append('.')
        }
    }

    private const val MAX_FAILOVER_ATTEMPTS = 32
}

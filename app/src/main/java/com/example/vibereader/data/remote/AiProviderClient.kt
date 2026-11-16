package com.example.vibereader.data.remote

import android.util.Log
import com.example.vibereader.model.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared chat client for every provider.
 *
 * Keeping request construction and response parsing in one place means the
 * reader assistant, wallpaper curator and fallback manager all understand the
 * same provider quirks. System prompts are flattened into the user message so
 * no gateway can reject them with HTTP 400 over an unsupported system role.
 */
object AiProviderClient {
    private const val TAG = "AiProviderClient"
    private const val AI_ERROR_TAG = "VibeReader_AI"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val MAX_ERROR_BODY_CHARS = 300
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val DEFAULT_ANTHROPIC_MAX_TOKENS = 4096
    private const val MAX_JSON_TEXT_DEPTH = 4
    private val CONTENT_TEXT_FIELDS = listOf("text", "content")
    private val THINK_BLOCK = Regex(
        "<think\\b[^>]*>.*?</think\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val THINK_UNCLOSED = Regex(
        "<think\\b[^>]*>.*",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val THINK_TAG = Regex("</?think\\b[^>]*>", RegexOption.IGNORE_CASE)
    private const val APP_REFERER = "https://github.com/VibeReader"
    private const val APP_TITLE = "VibeReader"
    private val OPENCODE_MODEL_PREFIX =
        Regex("^(?:opencode/)+", RegexOption.IGNORE_CASE)

    suspend fun executeChat(
        client: OkHttpClient,
        provider: AiProvider,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int? = null,
        temperature: Double = 0.3,
    ): String = withContext(Dispatchers.IO) {
        val normalizedModel = normalizeModelId(provider, model)
        val normalizedKey = apiKey.trim()
        val payload = if (provider.usesAnthropicMessagesApi) {
            buildAnthropicPayload(
                provider,
                normalizedModel,
                systemPrompt,
                userContent,
                maxTokens,
                temperature,
            )
        } else {
            buildOpenAiCompatiblePayload(
                provider,
                normalizedModel,
                systemPrompt,
                userContent,
                maxTokens,
                temperature,
            )
        }
        val request = Request.Builder()
            .url(provider.chatCompletionsUrl)
            .applyProviderAuth(provider, normalizedKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = client.newCall(request).awaitResponse()
            response.use { activeResponse ->
                val body = activeResponse.body?.string().orEmpty()
                if (!activeResponse.isSuccessful) {
                    Log.e(
                        AI_ERROR_TAG,
                        "API Error: ${activeResponse.code} - ${body.take(MAX_ERROR_BODY_CHARS)}",
                    )
                    Log.e(
                        TAG,
                        "API HTTP Error: ${activeResponse.code} - ${body.take(MAX_ERROR_BODY_CHARS)}",
                    )
                    val serverMessage = extractServerErrorMessage(body)
                    throw AiProviderHttpException(
                        statusCode = activeResponse.code,
                        serverMessage = serverMessage,
                        message = buildString {
                            append(provider.displayName)
                            append(" returned HTTP ")
                            append(activeResponse.code)
                            val detail = serverMessage
                                ?: body.take(MAX_ERROR_BODY_CHARS)
                                    .takeIf(String::isNotBlank)
                            if (detail != null) {
                                append(": ")
                                append(detail)
                            }
                        },
                    )
                }
                parseChatResponse(provider, body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AiProviderHttpException) {
            throw error
        } catch (error: SocketTimeoutException) {
            // Preserve the concrete timeout type for timeout-aware failover.
            Log.e(TAG, "Network timeout", error)
            throw error
        } catch (error: InterruptedIOException) {
            // Includes OkHttp callTimeout and other interrupted reads.
            Log.e(TAG, "Network interrupted/timeout", error)
            throw error
        } catch (error: IOException) {
            Log.e(TAG, "Network/IO Error", error)
            throw IOException(
                "Failed to connect to AI provider: ${error.localizedMessage ?: error.message}",
                error,
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unknown Error", error)
            throw IOException(
                "AI request failed: ${error.localizedMessage ?: error.message}",
                error,
            )
        }
    }

    /**
     * Suspending OkHttp call so stopping an endless quote fetch cancels the
     * in-flight request instead of waiting for its timeout.
     */
    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) {
                            continuation.resume(response)
                        } else {
                            response.close()
                        }
                    }
                },
            )
        }

    private fun buildOpenAiCompatiblePayload(
        provider: AiProvider,
        model: String,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int?,
        temperature: Double,
    ): JSONObject {
        // Flatten the system prompt directly into the user message for maximum
        // compatibility across ALL providers. Some gateways reject a separate
        // "system" role with HTTP 400, so a single user message is safest.
        val combinedContent = if (systemPrompt.isNotBlank()) {
            "Instructions: $systemPrompt\n\nTask: $userContent"
        } else {
            userContent
        }
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "user").put("content", combinedContent))
        }
        return JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("messages", messages)
            .apply {
                if (maxTokens != null) put("max_tokens", maxTokens)
            }
    }

    private fun buildAnthropicPayload(
        provider: AiProvider,
        model: String,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int?,
        temperature: Double,
    ): JSONObject {
        check(provider.usesAnthropicMessagesApi)
        val combinedContent = if (systemPrompt.isNotBlank()) {
            "Instructions: $systemPrompt\n\nTask: $userContent"
        } else {
            userContent
        }
        return JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens ?: DEFAULT_ANTHROPIC_MAX_TOKENS)
            .put("temperature", temperature)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", combinedContent),
                ),
            )
    }

    /**
     * OpenCode's OpenAI-compatible endpoint is already scoped to its gateway.
     * A model copied from CLI configuration may still carry an `opencode/`
     * prefix, which the REST endpoint rejects as an unknown model ID.
     */
    fun normalizeModelId(provider: AiProvider, model: String): String {
        val trimmed = model.trim()
        if (provider != AiProvider.OPENCODE) return trimmed
        return trimmed
            .replaceFirst(OPENCODE_MODEL_PREFIX, "")
            .trim()
    }

    private fun Request.Builder.applyProviderAuth(
        provider: AiProvider,
        apiKey: String,
    ): Request.Builder = apply {
        when (provider) {
            AiProvider.ANTHROPIC -> {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
            }

            AiProvider.OPENROUTER -> {
                header("Authorization", "Bearer $apiKey")
                header("HTTP-Referer", APP_REFERER)
                header("X-Title", APP_TITLE)
            }

            AiProvider.OPENCODE -> {
                header("Authorization", "Bearer $apiKey")
                header("Content-Type", "application/json")
                header("Accept", "application/json")
            }

            else -> header("Authorization", "Bearer $apiKey")
        }
    }

    /**
     * OpenAI-compatible gateways wrap the actionable text in different JSON
     * envelopes (`error.message`, `message`, `detail`, or a plain `error`
     * string). Extracting it lets a single-key failure surface exactly what the
     * provider said instead of a generic quota message.
     */
    private fun extractServerErrorMessage(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val extracted = runCatching {
            val json = JSONObject(trimmed)
            val error = json.opt("error")
            val nested = when (error) {
                is JSONObject -> sequenceOf("message", "detail", "type", "code")
                    .mapNotNull { key ->
                        error.optString(key).trim().takeIf(String::isNotBlank)
                    }
                    .firstOrNull()

                is String -> error.trim().takeIf(String::isNotBlank)
                else -> null
            }
            nested
                ?: sequenceOf("message", "detail")
                    .mapNotNull { key ->
                        json.optString(key).trim().takeIf(String::isNotBlank)
                    }
                    .firstOrNull()
        }.getOrNull()
        return extracted
            ?.take(MAX_ERROR_BODY_CHARS)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun parseChatResponse(provider: AiProvider, body: String): String {
        val text = if (provider.usesAnthropicMessagesApi) {
            // Only text content blocks are user-visible output. Thinking /
            // reasoning blocks must never leak into answers or quotes.
            val root = JSONObject(body)
            contentText(root.opt("content"))
                ?: throw IOException(
                    "${provider.displayName} returned an empty response " +
                        "(no text content).",
                )
        } else {
            val choices = JSONObject(body).optJSONArray("choices")
                ?: throw IOException("${provider.displayName} returned an invalid response.")
            val choice = choices.optJSONObject(0)
                ?: throw IOException("${provider.displayName} returned no choices.")
            val message = choice.optJSONObject("message")
            // Exactly message.content: never fall back to reasoning or
            // reasoning_content. Those fields are internal thinking text and
            // used to leak into the UI and the quotes list.
            val content = contentText(message?.opt("content"))
            content ?: throw IOException(
                buildString {
                    append(provider.displayName)
                    append(" returned an empty response")
                    val finishReason = choice.optString("finish_reason")
                        .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    if (finishReason != null) {
                        append(" (finish_reason=")
                        append(finishReason)
                        append(')')
                    }
                    append(". The model produced no message content; ")
                    append("try a different model or increase max tokens.")
                },
            )
        }
        return text.trim().takeIf { it.isNotBlank() }
            ?: throw IOException("${provider.displayName} returned only blank text.")
    }

    /**
     * Extracts only visible `content` text. JSON `null`, [JSONObject.NULL], and
     * the literal strings `"null"`/`"undefined"` are rejected. Content arrays
     * are accepted for providers that return text blocks, but thinking,
     * reasoning, and non-text blocks are ignored.
     */
    private fun contentText(value: Any?, depth: Int = 0): String? {
        if (value == null || value == JSONObject.NULL || depth > MAX_JSON_TEXT_DEPTH) return null
        return when (value) {
            is String -> cleanedModelText(value)

            is JSONArray -> (0 until value.length())
                .asSequence()
                .mapNotNull { index -> contentText(value.opt(index), depth + 1) }
                .joinToString("\n")
                .takeIf(String::isNotBlank)

            is JSONObject -> {
                val type = value.optString("type")
                if (type.isNotBlank() && !type.equals("text", ignoreCase = true)) {
                    null
                } else {
                    CONTENT_TEXT_FIELDS
                        .asSequence()
                        .mapNotNull { field -> contentText(value.opt(field), depth + 1) }
                        .firstOrNull()
                }
            }

            else -> cleanedModelText(value.toString())
        }
    }

    private fun cleanedModelText(raw: String): String? = raw
        .replace(THINK_BLOCK, " ")
        .replace(THINK_UNCLOSED, " ")
        .replace(THINK_TAG, " ")
        .trim()
        .takeIf {
            it.isNotEmpty() &&
                !it.equals("null", ignoreCase = true) &&
                !it.equals("undefined", ignoreCase = true)
        }
}

class AiProviderHttpException(
    val statusCode: Int,
    message: String,
    /** Actionable message extracted from the provider's error JSON, if any. */
    val serverMessage: String? = null,
) : IOException(message) {
    /**
     * Any HTTP failure should be retried against the next configured provider:
     * an invalid key, missing model or rate limit on one gateway should not
     * abort a request that another provider can serve.
     */
    val isRetryable: Boolean
        get() = statusCode >= 400
}
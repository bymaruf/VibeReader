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
    private const val APP_REFERER = "https://github.com/VibeReader"
    private const val APP_TITLE = "VibeReader"

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
        val payload = if (provider.usesAnthropicMessagesApi) {
            buildAnthropicPayload(
                provider,
                model,
                systemPrompt,
                userContent,
                maxTokens,
                temperature,
            )
        } else {
            buildOpenAiCompatiblePayload(
                provider,
                model,
                systemPrompt,
                userContent,
                maxTokens,
                temperature,
            )
        }
        val request = Request.Builder()
            .url(provider.chatCompletionsUrl)
            .applyProviderAuth(provider, apiKey)
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
                    throw AiProviderHttpException(
                        statusCode = activeResponse.code,
                        message = "${provider.displayName} returned HTTP ${activeResponse.code}: " +
                            body.take(MAX_ERROR_BODY_CHARS),
                    )
                }
                parseChatResponse(provider, body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AiProviderHttpException) {
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

            else -> header("Authorization", "Bearer $apiKey")
        }
    }

    private fun parseChatResponse(provider: AiProvider, body: String): String {
        val text = if (provider.usesAnthropicMessagesApi) {
            val content = JSONObject(body).optJSONArray("content")
                ?: throw IOException("${provider.displayName} returned an invalid response.")
            buildString {
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "text") {
                        append(block.optString("text"))
                    }
                }
            }
        } else {
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
        }
        return text.trim().takeIf(String::isNotBlank)
            ?: throw IOException("AI provider returned an empty response.")
    }
}

class AiProviderHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message) {
    /**
     * Any HTTP failure should be retried against the next configured provider:
     * an invalid key, missing model or rate limit on one gateway should not
     * abort a request that another provider can serve.
     */
    val isRetryable: Boolean
        get() = statusCode >= 400
}
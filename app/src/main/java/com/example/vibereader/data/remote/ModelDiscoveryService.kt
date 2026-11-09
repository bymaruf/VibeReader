package com.example.vibereader.data.remote

import com.example.vibereader.model.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DiscoveredModel(
    val id: String,
    val name: String,
    val isFree: Boolean = false,
    val supportsImages: Boolean = false,
    val isFast: Boolean = false,
    val isHighPrecision: Boolean = false,
) {
    /** Premium reasoning/coding models are surfaced with a PRO badge. */
    val isPro: Boolean
        get() = isHighPrecision && !isFree

    val tierLabel: String
        get() = when {
            isFree -> "FREE"
            isPro -> "PRO"
            else -> "PAID"
        }

    val badges: List<String>
        get() = buildList {
            add(tierLabel)
            if (isFast) add("Fast")
            if (supportsImages) add("Image input")
        }
}

class ModelDiscoveryService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetchAvailableModels(
        provider: AiProvider,
        apiKey: String,
    ): List<DiscoveredModel> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Add an API key before discovering models." }
        validateApiKeyIfNeeded(provider, apiKey)
        val request = createRequest(provider, apiKey)
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ModelDiscoveryException(
                    provider,
                    response.code,
                    body,
                )
            }
            try {
                parseModels(provider, JSONObject(body))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw IOException("${provider.displayName} returned an invalid model list.", error)
            }
        }
    }

    /**
     * OpenRouter's catalog is public and returns 200 even for a bad key, so its
     * dedicated key endpoint is checked first. Other providers reject an
     * invalid key on the catalog request itself.
     */
    private fun validateApiKeyIfNeeded(provider: AiProvider, apiKey: String) {
        if (provider != AiProvider.OPENROUTER) return
        val request = Request.Builder()
            .url(OPENROUTER_KEY_URL)
            .applyDiscoveryAuth(provider, apiKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ModelDiscoveryException(
                    provider,
                    response.code,
                    response.body?.string().orEmpty(),
                )
            }
        }
    }

    private fun createRequest(provider: AiProvider, apiKey: String): Request {
        return Request.Builder()
            .url(provider.modelsUrl)
            .applyDiscoveryAuth(provider, apiKey)
            .get()
            .build()
    }

    private fun Request.Builder.applyDiscoveryAuth(
        provider: AiProvider,
        apiKey: String,
    ): Request.Builder = apply {
        when (provider) {
            AiProvider.GEMINI -> header("x-goog-api-key", apiKey)

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

    private fun parseModels(provider: AiProvider, response: JSONObject): List<DiscoveredModel> {
        val items = when (provider) {
            AiProvider.GEMINI -> response.optJSONArray("models")
            else -> response.optJSONArray("data")
        } ?: throw IOException("${provider.displayName} did not return a models list.")
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val model = parseModel(provider, item) ?: continue
                if (isSupportedChatModel(provider, item, model.id)) add(model)
            }
        }.distinctBy(DiscoveredModel::id)
            .sortedWith(compareBy<DiscoveredModel> { !it.isFree }.thenBy { it.name.lowercase() })
    }

    private fun parseModel(provider: AiProvider, item: JSONObject): DiscoveredModel? {
        val rawId = when (provider) {
            AiProvider.GEMINI -> item.optString("name").removePrefix("models/")
            else -> item.optString("id")
        }.takeIf(String::isNotBlank) ?: return null
        val displayName = sequenceOf(
            item.optString("displayName"),
            item.optString("display_name"),
            item.optString("name"),
        ).firstOrNull {
            it.isNotBlank() && it != rawId && it != "models/$rawId"
        } ?: rawId
        val inputModalities = item.optJSONObject("architecture")
            ?.optJSONArray("input_modalities")
            ?: item.optJSONObject("modalities")?.optJSONArray("input")
        val supportsImages = containsImage(inputModalities) ||
            rawId.contains("vision", ignoreCase = true) ||
            rawId.contains("image", ignoreCase = true) ||
            (provider == AiProvider.GEMINI && rawId.startsWith("gemini-", ignoreCase = true))
        val idLower = rawId.lowercase()
        return DiscoveredModel(
            id = rawId,
            name = displayName,
            isFree = isFreeModel(provider, rawId, item),
            supportsImages = supportsImages,
            isFast = listOf(
                "flash",
                "turbo",
                "instant",
                "haiku",
                "schnell",
                "mini",
                "nano",
                "lite",
            ).any { containsModelToken(idLower, it) },
            isHighPrecision = listOf("opus", "pro", "o1", "o3", "o4")
                .any { containsModelToken(idLower, it) } ||
                idLower.contains("reason") ||
                idLower.contains("thinking") ||
                idLower.contains("deepseek-r1"),
        )
    }

    private fun containsModelToken(id: String, token: String): Boolean =
        id.split('-', '.', '/', ':', '_', ' ')
            .any { it.equals(token, ignoreCase = true) }

    private fun isSupportedChatModel(
        provider: AiProvider,
        item: JSONObject,
        id: String,
    ): Boolean {
        val idLower = id.lowercase()
        if (listOf("embedding", "moderation", "whisper", "tts", "transcribe")
                .any(idLower::contains)
        ) {
            return false
        }
        if (provider == AiProvider.OPENCODE) {
            val type = item.optString("type").lowercase()
            if (type.isNotBlank() && type != "chat") return false
        }
        if (provider == AiProvider.GEMINI) {
            val methods = item.optJSONArray("supportedGenerationMethods")
            return methods == null || contains(methods, "generateContent")
        }
        return true
    }

    private fun isFreeModel(provider: AiProvider, id: String, item: JSONObject): Boolean {
        if (id.contains("free", ignoreCase = true)) return true
        return when (provider) {
            // Groq exposes its whole catalog on the free tier. Gemini's Flash /
            // Lite models have a public free tier, while Pro/Ultra are paid.
            AiProvider.GROQ -> true

            AiProvider.GEMINI -> {
                val idLower = id.lowercase()
                !listOf("pro", "ultra", "deep-research").any(idLower::contains)
            }

            AiProvider.OPENCODE -> {
                val textCost = item.optJSONObject("cost")?.optJSONObject("text")
                    ?: return false
                val inputPrice = textCost.optJSONObject("input")
                    ?.optDouble("price", -1.0) ?: -1.0
                val outputPrice = textCost.optJSONObject("output")
                    ?.optDouble("price", -1.0) ?: -1.0
                inputPrice == 0.0 && outputPrice == 0.0
            }

            AiProvider.OPENROUTER -> {
                val pricing = item.optJSONObject("pricing") ?: return false
                val promptPrice = pricing.optDouble("prompt", -1.0)
                val completionPrice = pricing.optDouble("completion", -1.0)
                val requestPrice = pricing.optDouble("request", 0.0)
                promptPrice == 0.0 && completionPrice == 0.0 && requestPrice == 0.0
            }

            AiProvider.OPENAI,
            AiProvider.ANTHROPIC,
            AiProvider.DEEPSEEK,
            -> false
        }
    }

    private fun containsImage(modalities: JSONArray?): Boolean =
        modalities != null && (0 until modalities.length()).any { index ->
            modalities.optString(index).contains("image", ignoreCase = true)
        }

    private fun contains(array: JSONArray, expected: String): Boolean =
        (0 until array.length()).any { index ->
            array.optString(index).equals(expected, ignoreCase = true)
        }

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val OPENROUTER_KEY_URL = "https://openrouter.ai/api/v1/key"
        private const val APP_REFERER = "https://github.com/VibeReader"
        private const val APP_TITLE = "VibeReader"
        private const val MAX_ERROR_BODY_CHARS = 500
    }
}

class ModelDiscoveryException(
    provider: AiProvider,
    val statusCode: Int,
    val responseBody: String,
) : IOException(
    "${provider.displayName} model discovery failed (HTTP $statusCode)." +
        responseBody.take(MAX_ERROR_BODY_CHARS).takeIf(String::isNotBlank)
            ?.let { " $it" }
            .orEmpty(),
) {
    val isInvalidKey: Boolean
        get() = statusCode == 400 ||
            statusCode == 401 ||
            statusCode == 403 ||
            responseBody.contains("invalid api key", ignoreCase = true) ||
            responseBody.contains("incorrect api key", ignoreCase = true)

    private companion object {
        const val MAX_ERROR_BODY_CHARS = 500
    }
}

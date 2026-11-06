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
    val isFreeTier: Boolean = false,
    val supportsImages: Boolean = false,
    val isFast: Boolean = false,
    val isHighPrecision: Boolean = false,
) {
    val badges: List<String>
        get() = buildList {
            if (isFreeTier) add("Free tier")
            if (isFast) add("Fast")
            if (isHighPrecision) add("High precision")
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
        val request = createRequest(provider, apiKey)
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ModelDiscoveryException(
                    provider,
                    response.code,
                    body.take(MAX_ERROR_BODY_CHARS),
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

    private fun createRequest(provider: AiProvider, apiKey: String): Request {
        val url = when (provider) {
            AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models"
            AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1/models"
            AiProvider.OPENAI -> "https://api.openai.com/v1/models"
            AiProvider.GROQ -> "https://api.groq.com/openai/v1/models"
            AiProvider.DEEPSEEK -> "https://api.deepseek.com/v1/models"
        }
        return Request.Builder()
            .url(url)
            .apply {
                if (provider == AiProvider.GEMINI) {
                    header("x-goog-api-key", apiKey)
                } else {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .get()
            .build()
    }

    private fun parseModels(provider: AiProvider, response: JSONObject): List<DiscoveredModel> {
        val items = when (provider) {
            AiProvider.GEMINI -> response.optJSONArray("models")
                ?: throw IOException("Gemini did not return a models list.")
            else -> response.optJSONArray("data")
                ?: throw IOException("${provider.displayName} did not return a models list.")
        }
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val model = parseModel(provider, item) ?: continue
                if (isSupportedChatModel(provider, item, model.id)) add(model)
            }
        }.distinctBy(DiscoveredModel::id)
            .sortedWith(compareBy<DiscoveredModel> { !it.isFreeTier }.thenBy { it.name.lowercase() })
    }

    private fun parseModel(provider: AiProvider, item: JSONObject): DiscoveredModel? {
        val rawId = when (provider) {
            AiProvider.GEMINI -> item.optString("name")
                .removePrefix("models/")
            else -> item.optString("id")
        }.takeIf(String::isNotBlank) ?: return null
        val displayName = item.optString("displayName")
            .takeIf(String::isNotBlank)
            ?: item.optString("name").takeIf { it.isNotBlank() && it != "models/$rawId" }
            ?: rawId
        val architecture = item.optJSONObject("architecture")
        val modalities = architecture?.optJSONArray("input_modalities")
        val supportsImages = containsImage(modalities) ||
            rawId.contains("vision", ignoreCase = true) ||
            rawId.contains("image", ignoreCase = true) ||
            (provider == AiProvider.GEMINI && rawId.startsWith("gemini-", ignoreCase = true))
        val isFree = isFreeModel(provider, rawId, item)
        val idLower = rawId.lowercase()
        return DiscoveredModel(
            id = rawId,
            name = displayName,
            isFreeTier = isFree,
            supportsImages = supportsImages,
            isFast = listOf("flash", "turbo", "instant", "haiku", "schnell", "mini")
                .any(idLower::contains),
            isHighPrecision = listOf("opus", "pro", "reason", "o1", "o3", "o4", "deepseek-r1")
                .any(idLower::contains),
        )
    }

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
        if (provider == AiProvider.GEMINI) {
            val methods = item.optJSONArray("supportedGenerationMethods")
            return methods == null || contains(methods, "generateContent")
        }
        return true
    }

    private fun isFreeModel(provider: AiProvider, id: String, item: JSONObject): Boolean {
        if (id.contains(":free", ignoreCase = true) ||
            id.contains("/free", ignoreCase = true) ||
            id.contains("free", ignoreCase = true)
        ) {
            return true
        }
        if (provider == AiProvider.GEMINI || provider == AiProvider.GROQ) return true
        if (provider != AiProvider.OPENROUTER) return false
        val pricing = item.optJSONObject("pricing") ?: return false
        return pricing.optDouble("prompt", -1.0) == 0.0 &&
            pricing.optDouble("completion", -1.0) == 0.0
    }

    private fun containsImage(modalities: JSONArray?): Boolean =
        modalities != null && contains(modalities, "image")

    private fun contains(array: JSONArray, expected: String): Boolean =
        (0 until array.length()).any { index ->
            array.optString(index).equals(expected, ignoreCase = true)
        }

    companion object {
        private const val MAX_ERROR_BODY_CHARS = 500
    }
}

class ModelDiscoveryException(
    provider: AiProvider,
    val statusCode: Int,
    responseBody: String,
) : IOException(
    "${provider.displayName} model discovery failed (HTTP $statusCode)." +
        responseBody.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty(),
)

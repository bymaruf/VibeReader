package com.example.vibereader.data.remote

import android.content.Context
import com.example.vibereader.core.secure_storage.ApiKeyStore
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.domain.QuoteQualityValidator
import com.example.vibereader.model.AiProvider
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

class WallpaperQuoteCurator(context: Context) {
    private val appContext = context.applicationContext
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun curate(
        bookTitle: String,
        bookAuthor: String?,
        savedText: List<String>,
        chapterText: List<String>,
    ): CuratedWallpaperQuote {
        val candidates = buildCandidates(savedText, chapterText)
        if (candidates.isEmpty()) {
            throw IOException("No complete, meaningful quote was found in the saved text or book.")
        }

        var setupFailure: Exception? = null
        val apiKeyStore = try {
            ApiKeyStore(appContext)
        } catch (error: Exception) {
            setupFailure = error
            null
        }
        val keyVault = try {
            AiKeyVault(appContext)
        } catch (error: Exception) {
            setupFailure = error
            null
        }
        val configs = try {
            apiKeyStore
                ?.getEnabledProviderConfigs(apiKeyStore.getSelectedAiProvider())
                .orEmpty()
                .filter { it.isEnabled && it.apiKey.isNotBlank() }
        } catch (error: Exception) {
            setupFailure = error
            emptyList()
        }

        var aiFailure: Exception? = null
        for (config in configs) {
            try {
                // Model selection is persisted per provider in DataStore; key/failover
                // routing (configs above) is unchanged.
                val model = keyVault?.getAiModel(config.provider)
                    ?.takeIf(String::isNotBlank)
                    ?: config.provider.defaultModel
                return CuratedWallpaperQuote(
                    quote = requestCuratedCandidate(
                        config.provider,
                        config.apiKey,
                        model,
                        bookTitle,
                        bookAuthor,
                        candidates,
                    ),
                    source = QuoteSelectionSource.AI,
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                aiFailure = error
            }
        }

        val localChoice = candidates.maxByOrNull(::localQualityScore)
            ?: throw IOException("No complete, meaningful quote was found in the saved text or book.")
        return CuratedWallpaperQuote(
            quote = localChoice,
            source = if (aiFailure == null) QuoteSelectionSource.LOCAL else QuoteSelectionSource.LOCAL_AFTER_AI_FAILURE,
            fallbackReason = aiFailure?.message ?: setupFailure?.message
                ?: if (configs.isEmpty()) "No AI provider key is configured." else null,
        )
    }

    private fun buildCandidates(savedText: List<String>, chapterText: List<String>): List<String> =
        (savedText.asSequence() + chapterText.asSequence().flatMap(::extractSentences))
            .map { it.normalizeWhitespace() }
            .filter { it.length <= MAX_CANDIDATE_CHARS }
            .filter(QuoteQualityValidator::isValidQuote)
            .distinct()
            .take(MAX_CANDIDATES)
            .toList()

    private fun extractSentences(text: String): Sequence<String> =
        text.replace(WHITESPACE, " ")
            .split(SENTENCE_BOUNDARY)
            .asSequence()
            .map(String::trim)

    private suspend fun requestCuratedCandidate(
        provider: AiProvider,
        apiKey: String,
        model: String,
        bookTitle: String,
        bookAuthor: String?,
        candidates: List<String>,
    ): String {
        val systemPrompt = """
            Select the strongest meaningful, complete quote from the supplied candidates for a book wallpaper.
            Candidates are exact excerpts from the user's saved reading text; do not rewrite, invent, or attribute them.
            Return only a JSON object with one integer field named "index", using the candidate's zero-based index.
            Choose a quote with a complete thought and suitable length. The book title and author are context only.
        """.trimIndent()
        val userContent = JSONObject()
            .put("bookTitle", bookTitle)
            .put("bookAuthor", bookAuthor.orEmpty())
            .put("candidates", JSONArray(candidates))
            .toString()
        val responseText = AiProviderClient.executeChat(
            client = httpClient,
            provider = provider,
            apiKey = apiKey,
            model = model,
            systemPrompt = systemPrompt,
            userContent = userContent,
            maxTokens = MAX_QUOTE_TOKENS,
            temperature = 0.1,
        )
        val index = JSONObject(responseText).getInt("index")
        val selected = candidates.getOrNull(index)
            ?: throw IOException("The AI provider selected an invalid quote candidate.")
        if (!QuoteQualityValidator.isValidQuote(selected)) {
            throw IOException("The AI provider selected a quote that failed quality checks.")
        }
        return selected
    }

    private fun localQualityScore(quote: String): Int {
        val wordCount = quote.split(WHITESPACE).size
        val idealWordCount = 18
        return 100 - (wordCount - idealWordCount).absoluteValue
    }

    private fun String.normalizeWhitespace(): String = replace(WHITESPACE, " ").trim()

    companion object {
        private const val MAX_CANDIDATES = 100
        private const val MAX_CANDIDATE_CHARS = 600
        private const val MAX_QUOTE_TOKENS = 40
        private const val REQUEST_TIMEOUT_SECONDS = 20L
        private val WHITESPACE = Regex("""\s+""")
        private val SENTENCE_BOUNDARY = Regex("""(?<=[.!?])\s+""")
    }
}

data class CuratedWallpaperQuote(
    val quote: String,
    val source: QuoteSelectionSource,
    val fallbackReason: String? = null,
)

enum class QuoteSelectionSource {
    AI,
    LOCAL,
    LOCAL_AFTER_AI_FAILURE,
}

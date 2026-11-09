package com.example.vibereader.data.remote

import android.content.Context
import android.util.Log
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.domain.QuoteQualityValidator
import com.example.vibereader.domain.model.EBookDocument
import com.example.vibereader.domain.model.EBookQuote
import com.example.vibereader.model.AiProvider
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Format-independent facade over [WallpaperQuoteCurator].
 *
 * Callers pass the [EBookDocument] that was produced from the file header, so
 * an EPUB and a PDF reach the AI with the same title/author context.
 *
 * This class also exposes the human-in-the-loop *fetch* stage: the AI only
 * proposes a batch of high-quality quotes. Nothing is rendered until the user
 * approves individual quotes via `isApprovedForWallpaper`.
 */
class AiQuoteCurator(context: Context) {
    private val appContext = context.applicationContext
    private val delegate = WallpaperQuoteCurator(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun curate(
        document: EBookDocument,
        savedText: List<String>,
        chapterText: List<String>,
    ): CuratedWallpaperQuote = delegate.curate(
        bookTitle = document.title,
        bookAuthor = document.author,
        savedText = savedText,
        chapterText = chapterText,
    )

    suspend fun curate(
        bookTitle: String,
        bookAuthor: String?,
        savedText: List<String>,
        chapterText: List<String>,
    ): CuratedWallpaperQuote = delegate.curate(
        bookTitle = bookTitle,
        bookAuthor = bookAuthor,
        savedText = savedText,
        chapterText = chapterText,
    )

    /**
     * Fetches ONE batch of new quotes from the first provider that succeeds.
     *
     * Endless fetching is orchestrated by the caller: it repeatedly calls this
     * method with every already-stored quote passed as [alreadyCollected], so
     * each round only returns genuinely new material. There is no total quote
     * cap here; [DEFAULT_BATCH_SIZE] only sizes a single AI request.
     */
    suspend fun fetchQuoteBatch(
        document: EBookDocument,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        alreadyCollected: List<String> = emptyList(),
    ): List<String> = fetchQuoteBatch(
        bookTitle = document.title,
        bookAuthor = document.author,
        batchSize = batchSize,
        alreadyCollected = alreadyCollected,
    )

    suspend fun fetchQuoteBatch(
        bookTitle: String,
        bookAuthor: String?,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        alreadyCollected: List<String> = emptyList(),
    ): List<String> {
        val keyStore = AiKeyVault(appContext)
        val configs = keyStore
            .getEnabledProviderConfigs(keyStore.getSelectedProvider())
            .filter { it.isEnabled && it.apiKey.isNotBlank() }
        if (configs.isEmpty()) {
            throw IOException("Add an API key for the selected AI provider in Settings.")
        }

        var lastError: Throwable? = null
        val attempted = mutableListOf<String>()
        for (config in configs) {
            attempted += config.provider.shortLabel
            try {
                val model = keyStore.getAiModel(config.provider)
                    ?.takeIf(String::isNotBlank)
                    ?: config.provider.defaultModel
                val quotes = requestQuotes(
                    provider = config.provider,
                    apiKey = config.apiKey,
                    model = model,
                    bookTitle = bookTitle,
                    bookAuthor = bookAuthor,
                    count = batchSize.coerceAtLeast(1),
                    alreadyCollected = alreadyCollected,
                )
                if (quotes.isNotEmpty()) return quotes
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                Log.w(
                    TAG,
                    "Quote fetch failed on ${config.provider.displayName}; trying next.",
                    error,
                )
            }
        }

        val detail = lastError?.localizedMessage?.takeIf(String::isNotBlank)
        val message = if (lastError == null) {
            "The AI returned no new quotes. It may have exhausted this book."
        } else {
            "All ${configs.size} AI providers failed while fetching quotes " +
                "(${attempted.joinToString()})." +
                (detail?.let { " Last error: $it" } ?: "")
        }
        throw IOException(message, lastError)
    }

    private suspend fun requestQuotes(
        provider: AiProvider,
        apiKey: String,
        model: String,
        bookTitle: String,
        bookAuthor: String?,
        count: Int,
        alreadyCollected: List<String> = emptyList(),
    ): List<String> {
        val authorHint = bookAuthor?.takeIf(String::isNotBlank)?.let { " by $it" }.orEmpty()
        val systemPrompt =
            "You are a literary quote curator. Return only a JSON array of strings. " +
                "Each string must be a complete, standalone quote copied faithfully from the book. " +
                "Do not add commentary, markdown fences, numbering, or invented text."
        // Keep the prompt bounded even after many endless-fetch rounds.
        val exclusion = alreadyCollected
            .asReversed()
            .take(MAX_EXCLUSION_QUOTES)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n") { "- ${it.take(MAX_EXCLUSION_CHARS)}" }
            ?.let { "\nDo not repeat these already collected quotes:\n$it" }
            .orEmpty()
        val userContent =
            "Find up to $count new, profound, complete, and highly meaningful quotes from " +
                "the book '$bookTitle'$authorHint. Return as a JSON array of strings. " +
                "If fewer new quotes remain, return as many as you can find.$exclusion"
        val response = AiProviderClient.executeChat(
            client = httpClient,
            provider = provider,
            apiKey = apiKey,
            model = model,
            systemPrompt = systemPrompt,
            userContent = userContent,
            maxTokens = MAX_FETCH_TOKENS,
            temperature = 0.5,
        )
        return parseQuoteArray(response)
            .filter(QuoteQualityValidator::isValidQuote)
            .distinct()
            .take(count)
    }

    /**
     * Accepts a strict JSON string array, an object containing a `quotes`
     * array, an array of `{ "quote": ... }` objects, and — as a last resort —
     * a numbered/bulleted plain-text list. Models frequently drift between
     * these formats, and a fetch should not fail over a formatting quirk.
     */
    private fun parseQuoteArray(response: String): List<String> {
        val cleaned = response
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start >= 0 && end > start) {
            val jsonQuotes = runCatching {
                val array = JSONArray(cleaned.substring(start, end + 1))
                buildList {
                    for (index in 0 until array.length()) {
                        when (val item = array.opt(index)) {
                            is String -> add(item)
                            is JSONObject -> add(
                                item.optString("quote")
                                    .ifBlank { item.optString("text") },
                            )
                        }
                    }
                }
            }.getOrNull().orEmpty()
                .map(String::trim)
                .filter(String::isNotEmpty)
            if (jsonQuotes.isNotEmpty()) return jsonQuotes
        }

        // Some models wrap the array in an object: { "quotes": [...] }.
        runCatching {
            val objectQuotes = JSONObject(cleaned).optJSONArray("quotes")
                ?: return@runCatching emptyList<String>()
            buildList {
                for (index in 0 until objectQuotes.length()) {
                    objectQuotes.optString(index).trim()
                        .takeIf(String::isNotEmpty)
                        ?.let(::add)
                }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        // Last resort: plain-text numbered or bulleted list.
        return cleaned.lineSequence()
            .map(String::trim)
            .map { it.removePrefix("- ").removePrefix("* ").removePrefix("• ") }
            .map { it.replace(Regex("^\\d+[.)]\\s*"), "") }
            .map { it.trim('"', '\'', ' ', ',') }
            .filter { it.length >= 20 }
            .toList()
    }

    /**
     * Filters saved excerpts through the shared quality gate before they are
     * offered to the AI or to the local fallback scorer.
     */
    fun validSavedText(quotes: List<EBookQuote>): List<String> =
        quotes
            .filter(QuoteQualityValidator::isValidQuote)
            .map { it.selectedText }

    companion object {
        /**
         * Size of a single AI request, not a limit on the library. The caller
         * keeps requesting batches until the user stops or the provider runs
         * out of new material.
         */
        const val DEFAULT_BATCH_SIZE = 10
        private const val MAX_EXCLUSION_QUOTES = 120
        private const val MAX_EXCLUSION_CHARS = 80
        private const val MAX_FETCH_TOKENS = 4096
        private const val FETCH_TIMEOUT_SECONDS = 150L
        private const val TAG = "AiQuoteCurator"
    }
}

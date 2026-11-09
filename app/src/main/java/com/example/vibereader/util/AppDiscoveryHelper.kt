package com.example.vibereader.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import java.util.Locale

/** An installed app that can process a text lookup/dictionary intent. */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val activityName: String? = null,
)

/**
 * Discovers installed applications suitable for dictionary/translation lookups
 * from the reader (fired via `ACTION_PROCESS_TEXT`).
 */
object AppDiscoveryHelper {

    /**
     * Queries apps that can handle text lookup and returns likely
     * dictionary/translation tools, filtering out AI assistants and unrelated
     * generic handlers where possible.
     */
    fun getAvailableDictionaryApps(context: Context): List<AppInfo> {
        val packageManager = context.packageManager
        val processTextIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "word")
        }
        val processTextResults = queryIntentActivities(packageManager, processTextIntent)

        // Some dictionary/translator apps only advertise ACTION_SEND for text;
        // merge them in but keep the same strict filtering.
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "word")
        }
        val sendResults = queryIntentActivities(packageManager, sendIntent)

        val merged = LinkedHashMap<String, AppInfo>()
        (processTextResults + sendResults).forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val packageName = activityInfo.packageName ?: return@forEach
            if (packageName == context.packageName) return@forEach
            if (merged.containsKey(packageName)) return@forEach

            val appName = resolveInfo.loadLabel(packageManager).toString().ifBlank { packageName }
            if (!isLikelyDictionaryOrTranslator(packageName, appName)) return@forEach

            val icon = runCatching { resolveInfo.loadIcon(packageManager) }.getOrNull()
                ?: runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
                ?: return@forEach
            merged[packageName] = AppInfo(
                packageName = packageName,
                appName = appName,
                icon = icon,
                activityName = activityInfo.name,
            )
        }

        return merged.values.sortedBy { it.appName.lowercase(Locale.ROOT) }
    }

    /**
     * Resolves the best `ACTION_PROCESS_TEXT` activity for [packageName], if any.
     */
    fun resolveProcessTextActivity(context: Context, packageName: String): String? {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "word")
            setPackage(packageName)
        }
        return queryIntentActivities(context.packageManager, intent)
            .firstOrNull { it.activityInfo?.packageName == packageName }
            ?.activityInfo
            ?.name
    }

    private fun queryIntentActivities(
        packageManager: PackageManager,
        intent: Intent,
    ): List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

    private fun isLikelyDictionaryOrTranslator(packageName: String, appName: String): Boolean {
        val haystack = "$packageName $appName".lowercase(Locale.ROOT)

        // Exclude AI assistants / generic chat & read-aloud handlers that often
        // register PROCESS_TEXT but are not dictionary/translation apps.
        val excludedTokens = listOf(
            "chatgpt",
            "openai",
            "gemini",
            "bard",
            "copilot",
            "assistant",
            "claude",
            "perplexity",
            "readaloud",
            "read.aloud",
            "tts",
            "texttospeech",
            "speech",
            "voice",
            "browser",
            "chrome",
            "firefox",
            "launcher",
            "keyboard",
            "inputmethod",
            "keep",
            "notes",
            "notepad",
            "docs",
            "gmail",
            "messages",
            "sms",
            "whatsapp",
            "telegram",
            "facebook",
            "twitter",
            "reddit",
        )
        if (excludedTokens.any { haystack.contains(it) }) return false

        val includedTokens = listOf(
            "dict",
            "dictionary",
            "translate",
            "translator",
            "translation",
            "word",
            "vocab",
            "vocabulary",
            "thesaurus",
            "meaning",
            "definition",
            "define",
            "lexicon",
            "linguee",
            "reverso",
            "deepl",
            "pons",
            "collins",
            "oxford",
            "cambridge",
            "merriam",
            "wiktionary",
            "glosbe",
            "bangla",
            "bengali",
            "spanish",
            "english",
            "hindi",
            "arabic",
            "french",
            "german",
            "chinese",
            "japanese",
            "korean",
        )
        return includedTokens.any { haystack.contains(it) }
    }
}

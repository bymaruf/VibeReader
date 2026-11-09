package com.example.vibereader.util

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/** An installed app that can process selected text. */
data class DictionaryApp(
    val packageName: String,
    val label: String,
)

/**
 * Routes a selected word to a user-chosen text-processing app, or falls back to
 * a Google definition search when no custom app is configured.
 */
object DictionaryRouter {
    private const val TAG = "DictionaryRouter"

    /**
     * Opens [word] in the app identified by [customPackageName]. When that is
     * null/blank (or the app was uninstalled), uses a web search for its
     * definition instead.
     */
    fun launchDictionary(
        context: Context,
        word: String,
        customPackageName: String? = null,
    ) {
        val cleanWord = word.trim()
        if (cleanWord.isEmpty()) return

        val packageName = customPackageName?.takeIf(String::isNotBlank)
        if (packageName != null) {
            if (launchInApp(context, cleanWord, packageName)) return
            Log.w(TAG, "Selected dictionary app '$packageName' could not handle the word; using fallback")
        }
        launchWebFallback(context, cleanWord)
    }

    /**
     * Installed activities that advertise lookup intents for plain text,
     * resolved and filtered through [AppDiscoveryHelper].
     */
    fun installedDictionaryApps(context: Context): List<DictionaryApp> =
        AppDiscoveryHelper.getAvailableDictionaryApps(context).map { app ->
            DictionaryApp(
                packageName = app.packageName,
                label = app.appName,
            )
        }

    /** A single token with no whitespace is eligible for the Dictionary action. */
    fun isSingleWord(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && trimmed.none(Char::isWhitespace)
    }

    /** Query sent to Google when no custom dictionary app is configured. */
    fun definitionQuery(word: String): String = "define ${word.trim()}"

    private fun launchInApp(context: Context, word: String, packageName: String): Boolean {
        // 1) Preferred: ACTION_PROCESS_TEXT resolved to the app's specific activity.
        val processIntent = processTextIntent(context, word, packageName)
        if (tryStart(context, processIntent)) return true

        // 2) Fallback: ACTION_SEND to the target package (some translators/dicts
        // handle shared text but not PROCESS_TEXT components reliably).
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, word)
            setPackage(packageName)
        }
        if (tryStart(context, sendIntent)) return true

        // 3) Last in-app fallback: plain PROCESS_TEXT with package only.
        val genericIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, word)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, word)
            setPackage(packageName)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        // Keep the readonly flag boolean as well (some apps check either form).
        genericIntent.putExtra(Intent.EXTRA_PROCESS_TEXT, word)
        return tryStart(context, genericIntent)
    }

    private fun processTextIntent(context: Context, word: String, packageName: String): Intent {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, word)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        val activityName = AppDiscoveryHelper.resolveProcessTextActivity(context, packageName)
        if (activityName != null) {
            intent.component = ComponentName(packageName, activityName)
        } else {
            intent.setPackage(packageName)
        }
        return intent
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.forLaunchContext(context))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: NullPointerException) {
        false
    }

    private fun webSearchIntent(word: String): Intent =
        Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, definitionQuery(word))
        }

    private fun launchWebFallback(context: Context, word: String) {
        try {
            if (tryStart(context, webSearchIntent(word))) return
        } catch (_: Exception) {
            // continue to browser fallback
        }
        val uri = Uri.parse(
            "https://www.google.com/search?q=" + Uri.encode(definitionQuery(word)),
        )
        if (tryStart(context, Intent(Intent.ACTION_VIEW, uri))) return
        Toast.makeText(
            context,
            "No app is available to look up this word.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun Intent.forLaunchContext(context: Context): Intent = apply {
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

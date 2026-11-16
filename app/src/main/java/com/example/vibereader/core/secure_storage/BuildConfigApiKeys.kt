package com.example.vibereader.core.secure_storage

import com.example.vibereader.BuildConfig
import com.example.vibereader.model.AiProvider

/**
 * Build-time provider credentials supplied through the git-ignored
 * `local.properties` file and exposed by [BuildConfig].
 *
 * Only Gemini may read a credential from the build configuration. OpenCode and
 * OpenAI must use the exact key saved for that provider in
 * `EncryptedSharedPreferences`; falling back to another provider's build-time
 * key would cross the isolation boundary between provider accounts.
 *
 * Empty values and the `your_key_here` placeholder are treated as "not
 * configured", so the app never sends a placeholder as a real key.
 */
internal object BuildConfigApiKeys {
    private const val PLACEHOLDER = "your_key_here"

    fun forProvider(provider: AiProvider): String? = when (provider) {
        AiProvider.GEMINI -> BuildConfig.GEMINI_API_KEY.normalizedBuildTimeKey()
        else -> null
    }

    private fun String?.normalizedBuildTimeKey(): String? =
        this?.trim()
            ?.trim('"', '\'')
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != PLACEHOLDER }
}

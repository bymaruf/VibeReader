package com.example.vibereader.core.secure_storage

import com.example.vibereader.BuildConfig
import com.example.vibereader.model.AiProvider

/**
 * Build-time provider credentials supplied through the git-ignored
 * `local.properties` file and exposed by [BuildConfig].
 *
 * These are defaults only: a key saved by the user in Settings is always
 * preferred. Empty values and the `your_key_here` placeholder are treated as
 * "not configured", so the app never sends a placeholder as a real key.
 */
internal object BuildConfigApiKeys {
    private const val PLACEHOLDER = "your_key_here"

    fun forProvider(provider: AiProvider): String? = when (provider) {
        AiProvider.OPENROUTER -> BuildConfig.OPENROUTER_API_KEY
        AiProvider.OPENCODE -> BuildConfig.OPENCODE_API_KEY
        AiProvider.GEMINI -> BuildConfig.GEMINI_API_KEY
        AiProvider.ANTHROPIC -> BuildConfig.CLAUDE_API_KEY
        AiProvider.OPENAI -> BuildConfig.OPENAI_API_KEY
        AiProvider.GROQ -> BuildConfig.GROQ_API_KEY
        AiProvider.DEEPSEEK -> BuildConfig.DEEPSEEK_API_KEY
    }.trim().takeIf { it.isNotEmpty() && it != PLACEHOLDER }
}

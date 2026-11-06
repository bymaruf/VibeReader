package com.example.vibereader.model

enum class AiProvider(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String,
) {
    GEMINI(
        "Google Gemini (Free)",
        "https://generativelanguage.googleapis.com/v1beta/openai/",
        "gemini-3.8-flash",
        "AIzaSy...",
    ),
    GROQ(
        "Groq (Fast & Free)",
        "https://api.groq.com/openai/v1/",
        "llama-3.3-70b-versatile",
        "gsk_...",
    ),
    OPENROUTER(
        "OpenRouter (Free/Paid)",
        "https://openrouter.ai/api/v1/",
        "openrouter/free",
        "sk-or-v1-...",
    ),
    OPENAI(
        "OpenAI",
        "https://api.openai.com/v1/",
        "gpt-4o-mini",
        "sk-proj-...",
    ),
    DEEPSEEK(
        "DeepSeek",
        "https://api.deepseek.com/v1/",
        "deepseek-chat",
        "sk-...",
    ),
}

data class ProviderKeyConfig(
    val provider: AiProvider,
    val apiKey: String,
    val isEnabled: Boolean = true,
)

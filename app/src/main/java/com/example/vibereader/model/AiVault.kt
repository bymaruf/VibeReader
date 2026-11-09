package com.example.vibereader.model

/**
 * Every AI provider the app can talk to.
 *
 * [baseUrl] is the root of the provider's chat API. OpenAI-compatible providers
 * append [chatPath] (usually `chat/completions`); Anthropic uses its native
 * `messages` endpoint instead. [modelsUrl] is kept explicit because gateways
 * such as OpenCode expose their catalog from a different path than their chat
 * API.
 */
enum class AiProvider(
    val displayName: String,
    val shortLabel: String,
    val baseUrl: String,
    val modelsUrl: String,
    val defaultModel: String,
    val keyHint: String,
    val chatPath: String = "chat/completions",
) {
    OPENCODE(
        displayName = "OpenCode Zen",
        shortLabel = "OpenCode",
        baseUrl = "https://opencode.ai/inference/openai/v1/",
        modelsUrl = "https://opencode.ai/inference/v1/models",
        defaultModel = "deepseek-v4.1-flash",
        keyHint = "oc_sk_...",
    ),
    GEMINI(
        displayName = "Google Gemini",
        shortLabel = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
        modelsUrl = "https://generativelanguage.googleapis.com/v1beta/models",
        defaultModel = "gemini-3.8-flash",
        keyHint = "AIzaSy...",
    ),
    OPENAI(
        displayName = "OpenAI",
        shortLabel = "OpenAI",
        baseUrl = "https://api.openai.com/v1/",
        modelsUrl = "https://api.openai.com/v1/models",
        defaultModel = "gpt-4o-mini",
        keyHint = "sk-proj-...",
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        shortLabel = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/",
        modelsUrl = "https://openrouter.ai/api/v1/models",
        defaultModel = "openrouter/free",
        keyHint = "sk-or-v1-...",
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude",
        shortLabel = "Anthropic",
        baseUrl = "https://api.anthropic.com/v1/",
        modelsUrl = "https://api.anthropic.com/v1/models?limit=1000",
        defaultModel = "claude-sonnet-4-5",
        keyHint = "sk-ant-...",
        chatPath = "messages",
    ),
    GROQ(
        displayName = "Groq",
        shortLabel = "Groq",
        baseUrl = "https://api.groq.com/openai/v1/",
        modelsUrl = "https://api.groq.com/openai/v1/models",
        defaultModel = "llama-3.3-70b-versatile",
        keyHint = "gsk_...",
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        shortLabel = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1/",
        modelsUrl = "https://api.deepseek.com/v1/models",
        defaultModel = "deepseek-chat",
        keyHint = "sk-...",
    ),
    ;

    /** Fully qualified URL used for chat completions. */
    val chatCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/$chatPath"

    /** Anthropic speaks its own Messages API rather than OpenAI chat completions. */
    val usesAnthropicMessagesApi: Boolean
        get() = this == ANTHROPIC
}

data class ProviderKeyConfig(
    val provider: AiProvider,
    val apiKey: String,
    val isEnabled: Boolean = true,
)

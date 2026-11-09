package com.example.vibereader.data.local

import android.content.Context
import android.util.Log
import com.example.vibereader.core.secure_storage.ApiKeyStore
import com.example.vibereader.data.remote.DiscoveredModel
import com.example.vibereader.data.remote.ModelDiscoveryService
import com.example.vibereader.model.AiProvider
import com.example.vibereader.model.ProviderKeyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiKeyVault(
    context: Context,
    private val modelDiscoveryService: ModelDiscoveryService = ModelDiscoveryService(),
) {
    private val appContext = context.applicationContext

    // EncryptedSharedPreferences/keystore can throw on corrupted devices; never
    // crash callers (including Settings and AI failover initialization).
    private val apiKeyStore: ApiKeyStore? = runCatching { ApiKeyStore(appContext) }
        .onFailure { Log.w(TAG, "Secure API key storage unavailable.", it) }
        .getOrNull()

    private val modelPreferences: AiModelPreferences? = runCatching { AiModelPreferences(appContext) }
        .onFailure { Log.w(TAG, "AI model preferences unavailable.", it) }
        .getOrNull()

    fun getSelectedProvider(): AiProvider = runCatching {
        apiKeyStore?.getSelectedAiProvider()
    }.getOrNull() ?: AiProvider.OPENROUTER

    fun getProviderConfig(provider: AiProvider): ProviderKeyConfig = runCatching {
        apiKeyStore?.getProviderConfig(provider)
    }.getOrNull() ?: ProviderKeyConfig(provider = provider, apiKey = "", isEnabled = true)

    fun getEnabledProviderConfigs(primaryProvider: AiProvider): List<ProviderKeyConfig> =
        runCatching {
            apiKeyStore?.getEnabledProviderConfigs(primaryProvider)
        }.getOrNull().orEmpty()

    fun getApiKey(provider: AiProvider): String? = runCatching {
        apiKeyStore?.getApiKey(provider)
    }.getOrNull()

    fun getAiModel(provider: AiProvider): String? = runCatching {
        modelPreferences?.getSelectedModel(provider)?.trim()?.takeIf(String::isNotBlank)
    }.getOrNull()

    fun saveAiModel(provider: AiProvider, model: String) {
        runCatching {
            modelPreferences?.saveSelectedModel(provider, model)
        }.onFailure { Log.w(TAG, "Could not persist selected model for ${provider.name}.", it) }
    }

    /**
     * Persists a discovery/default model only when this provider has no saved
     * non-blank model. Returns the value that ended up persisted so callers can
     * reflect the user's explicit selection if one appeared during discovery.
     */
    fun saveAiModelIfAbsent(provider: AiProvider, model: String): String? = runCatching {
        modelPreferences?.saveSelectedModelIfAbsent(provider, model)
    }.onFailure {
        Log.w(TAG, "Could not persist default model for ${provider.name}.", it)
    }.getOrNull()

    suspend fun discoverModels(
        provider: AiProvider,
        apiKey: String,
    ): List<DiscoveredModel> = withContext(Dispatchers.IO) {
        modelDiscoveryService.fetchAvailableModels(provider, apiKey)
    }

    fun selectOptimalDefaultModel(
        models: List<DiscoveredModel>,
        provider: AiProvider,
    ): DiscoveredModel? {
        if (models.isEmpty()) return null

        val preferredIds = when (provider) {
            AiProvider.OPENCODE -> listOf(
                "deepseek-v4.1-flash",
                "gpt-5-nano",
                "gemini-3.5-flash-lite",
                "claude-haiku-4-5",
            )
            AiProvider.GEMINI -> listOf(
                "gemini-3.8-flash",
                "gemini-2.5-flash",
                "gemini-flash",
            )
            AiProvider.OPENAI -> listOf(
                "gpt-4o-mini",
                "gpt-5-nano",
                "gpt-5-mini",
                "gpt-4.1-mini",
            )
            AiProvider.OPENROUTER -> listOf(
                "claude-sonnet-4.5",
                "claude-sonnet-4-5",
                "gpt-4o-mini",
                "openrouter/auto",
            )
            AiProvider.ANTHROPIC -> listOf(
                "claude-sonnet-4-5",
                "claude-3-5-sonnet",
                "claude-haiku-4-5",
                "claude-3-5-haiku",
            )
            AiProvider.GROQ -> listOf(
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant",
            )
            AiProvider.DEEPSEEK -> listOf(
                "deepseek-chat",
                "deepseek-reasoner",
            )
        }

        preferredIds.forEach { preferred ->
            models.firstOrNull { it.id.equals(preferred, ignoreCase = true) }?.let { return it }
        }
        preferredIds.forEach { preferred ->
            models.firstOrNull {
                it.id.contains(preferred, ignoreCase = true) &&
                    !(provider == AiProvider.OPENCODE && it.isFree)
            }?.let { return it }
        }

        // OpenCode free-tier models are only callable from the OpenCode client,
        // so only fall back to them after every paid model has been considered.
        if (provider == AiProvider.OPENCODE) {
            models.firstOrNull { !it.isFree && it.isFast }?.let { return it }
            models.firstOrNull { !it.isFree }?.let { return it }
        }
        if (provider == AiProvider.OPENROUTER) {
            models.firstOrNull { !it.isFree && it.id.contains("claude", ignoreCase = true) }
                ?.let { return it }
        }

        return models.firstOrNull(DiscoveredModel::isFree) ?: models.firstOrNull()
    }

    fun saveProviderConfig(config: ProviderKeyConfig) {
        runCatching { apiKeyStore?.saveProviderConfig(config) }
            .onFailure { Log.w(TAG, "Could not save provider config for ${config.provider.name}.", it) }
    }

    fun setProviderEnabled(provider: AiProvider, enabled: Boolean) {
        runCatching { apiKeyStore?.setProviderEnabled(provider, enabled) }
            .onFailure { Log.w(TAG, "Could not update provider enablement for ${provider.name}.", it) }
    }

    fun setSelectedAiProvider(provider: AiProvider) {
        runCatching { apiKeyStore?.setSelectedAiProvider(provider) }
            .onFailure { Log.w(TAG, "Could not save selected AI provider.", it) }
    }

    private companion object {
        private const val TAG = "AiKeyVault"
    }
}

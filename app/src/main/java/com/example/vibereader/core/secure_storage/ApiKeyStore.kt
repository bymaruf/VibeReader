package com.example.vibereader.core.secure_storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.vibereader.model.AiProvider
import com.example.vibereader.model.ProviderKeyConfig

enum class ApiProvider(val preferenceKey: String) {
    OPENROUTER("openrouter_api_key"),
    CLAUDE("claude_api_key"),
    OPENAI("openai_api_key"),
}

@Suppress("DEPRECATION")
class ApiKeyStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = EncryptedSharedPreferences.create(
        appContext,
        PREFERENCES_FILE,
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveApiKey(apiKey: String) {
        saveApiKey(ApiProvider.OPENROUTER, apiKey)
    }

    fun saveApiKey(provider: ApiProvider, apiKey: String) {
        val editor = preferences.edit().putString(provider.preferenceKey, apiKey)
        if (provider == ApiProvider.OPENROUTER) editor.putString(API_KEY, apiKey)
        persistOrThrow(editor, "Could not persist the API key.")
    }

    fun getApiKey(provider: ApiProvider): String? =
        preferences.getString(provider.preferenceKey, null)

    fun saveApiKey(provider: AiProvider, apiKey: String) {
        val editor = preferences.edit().putString(providerKey(provider), apiKey)
        when (provider) {
            AiProvider.OPENROUTER -> editor
                .putString(ApiProvider.OPENROUTER.preferenceKey, apiKey)
                .putString(API_KEY, apiKey)
            AiProvider.OPENAI -> editor.putString(ApiProvider.OPENAI.preferenceKey, apiKey)
            AiProvider.GEMINI,
            AiProvider.GROQ,
            AiProvider.DEEPSEEK,
            -> Unit
        }
        persistOrThrow(editor, "Could not persist the API key.")
    }

    fun saveProviderConfig(config: ProviderKeyConfig) {
        setProviderEnabled(config.provider, config.isEnabled)
        if (config.apiKey.isBlank()) {
            clearApiKey(config.provider)
        } else {
            saveApiKey(config.provider, config.apiKey)
        }
    }

    fun setProviderEnabled(provider: AiProvider, enabled: Boolean) {
        preferences.edit().putBoolean(providerEnabledKey(provider), enabled).apply()
    }

    fun clearApiKey(provider: AiProvider) {
        val editor = preferences.edit().remove(providerKey(provider))
        when (provider) {
            AiProvider.OPENROUTER -> editor
                .remove(ApiProvider.OPENROUTER.preferenceKey)
                .remove(API_KEY)
            AiProvider.OPENAI -> editor.remove(ApiProvider.OPENAI.preferenceKey)
            AiProvider.GEMINI,
            AiProvider.GROQ,
            AiProvider.DEEPSEEK,
            -> Unit
        }
        persistOrThrow(editor, "Could not remove the saved API key.")
    }

    fun getApiKey(provider: AiProvider): String? {
        val savedKey = preferences.getString(providerKey(provider), null)
        if (!savedKey.isNullOrBlank()) return savedKey
        return when (provider) {
            AiProvider.OPENROUTER -> getApiKey(ApiProvider.OPENROUTER)
            AiProvider.OPENAI -> getApiKey(ApiProvider.OPENAI)
            AiProvider.GEMINI,
            AiProvider.GROQ,
            AiProvider.DEEPSEEK,
            -> null
        }
    }

    fun getProviderConfig(provider: AiProvider): ProviderKeyConfig =
        ProviderKeyConfig(
            provider = provider,
            apiKey = getApiKey(provider).orEmpty(),
            isEnabled = preferences.getBoolean(providerEnabledKey(provider), true),
        )

    fun getEnabledProviderConfigs(primaryProvider: AiProvider): List<ProviderKeyConfig> =
        listOf(getProviderConfig(primaryProvider)) + AiProvider.entries
            .filter { it != primaryProvider }
            .map(::getProviderConfig)
            .filter { it.isEnabled && it.apiKey.isNotBlank() }

    fun getSelectedAiProvider(): AiProvider =
        AiProvider.entries.firstOrNull {
            it.name == preferences.getString(SELECTED_AI_PROVIDER, null)
        } ?: AiProvider.OPENROUTER

    fun setSelectedAiProvider(provider: AiProvider) {
        preferences.edit().putString(SELECTED_AI_PROVIDER, provider.name).apply()
    }

    fun getAiModel(provider: AiProvider): String? =
        preferences.getString(aiModelKey(provider), null)

    fun saveAiModel(provider: AiProvider, model: String) {
        preferences.edit().putString(aiModelKey(provider), model).apply()
    }

    fun getApiKey(): String? =
        getApiKey(ApiProvider.OPENROUTER) ?: preferences.getString(API_KEY, null)

    fun clearApiKey() {
        clearApiKey(ApiProvider.OPENROUTER)
    }

    fun clearApiKey(provider: ApiProvider) {
        val editor = preferences.edit().remove(provider.preferenceKey)
        if (provider == ApiProvider.OPENROUTER) editor.remove(API_KEY)
        persistOrThrow(editor, "Could not remove the saved API key.")
    }

    private fun persistOrThrow(
        editor: android.content.SharedPreferences.Editor,
        errorMessage: String,
    ) {
        check(editor.commit()) { errorMessage }
    }

    private fun providerKey(provider: AiProvider): String =
        "ai_${provider.name.lowercase()}_api_key"

    private fun aiModelKey(provider: AiProvider): String =
        "ai_${provider.name.lowercase()}_model"

    private fun providerEnabledKey(provider: AiProvider): String =
        "ai_${provider.name.lowercase()}_enabled"

    private companion object {
        const val PREFERENCES_FILE = "vibereader_secure_preferences"
        const val API_KEY = "llm_api_key"
        const val SELECTED_AI_PROVIDER = "selected_ai_provider"
    }
}

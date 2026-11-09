package com.example.vibereader.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.vibereader.model.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.aiModelDataStore by preferencesDataStore(name = "ai_model_preferences")

/**
 * Persists the user's explicitly selected model per provider in DataStore.
 *
 * Required keys (independent per provider):
 * - SELECTED_OPENROUTER_MODEL
 * - SELECTED_OPENCODE_MODEL
 * - SELECTED_GEMINI_MODEL
 *
 * Other providers use the same `SELECTED_<PROVIDER>_MODEL` scheme so every
 * provider's selection is stored independently. This class does not touch API
 * keys, provider enablement, selected-provider routing, or failover/rotation.
 *
 * Reads always come from DataStore instead of a per-instance cache. Settings
 * keeps more than one [AiModelPreferences] instance alive (the ViewModel and
 * the provider card), and a per-instance cache allowed one instance to see a
 * stale/empty value and replace a model that another instance had just saved.
 */
class AiModelPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.aiModelDataStore

    /**
     * Returns the model currently persisted for [provider], or null when the
     * provider has no explicit selection yet.
     */
    fun getSelectedModel(provider: AiProvider): String? {
        ensureLegacyModelsMigrated()
        return readPersistedModel(provider)
    }

    /**
     * Persists an explicit user selection immediately under this provider's
     * dedicated key. A blank value removes only this provider's key.
     */
    fun saveSelectedModel(provider: AiProvider, model: String) {
        val normalized = model.trim()
        runBlocking(Dispatchers.IO) {
            dataStore.edit { preferences ->
                val key = preferenceKey(provider)
                if (normalized.isBlank()) {
                    preferences.remove(key)
                } else {
                    preferences[key] = normalized
                }
            }
        }
    }

    /**
     * Writes a discovery default only when this provider has no non-blank saved
     * model. The check and write happen inside one DataStore edit, so a user
     * selection made while model discovery was running always wins.
     *
     * @return the model that is actually persisted after the edit.
     */
    fun saveSelectedModelIfAbsent(provider: AiProvider, model: String): String? {
        val normalized = model.trim().takeIf(String::isNotBlank)
            ?: return readPersistedModel(provider)
        ensureLegacyModelsMigrated()
        return runBlocking(Dispatchers.IO) {
            var resolved: String? = normalized
            dataStore.edit { preferences ->
                val key = preferenceKey(provider)
                val existing = preferences[key]?.trim()?.takeIf(String::isNotBlank)
                if (existing != null) {
                    resolved = existing
                } else {
                    preferences[key] = normalized
                    resolved = normalized
                }
            }
            resolved
        }
    }

    private fun readPersistedModel(provider: AiProvider): String? = try {
        runBlocking(Dispatchers.IO) {
            dataStore.data.first()[preferenceKey(provider)]
        }?.trim()?.takeIf(String::isNotBlank)
    } catch (_: Exception) {
        null
    }

    /**
     * One-time, best-effort migration from the legacy `ai_<provider>_model`
     * keys in encrypted secure preferences. Only model keys are read; API
     * keys, enabled flags, selected-provider routing and failover settings are
     * never touched.
     */
    private fun ensureLegacyModelsMigrated() {
        if (legacyMigrationComplete) return
        synchronized(legacyMigrationLock) {
            if (legacyMigrationComplete) return
            val legacyModels = readLegacyModels()
            if (legacyModels.isNotEmpty()) {
                try {
                    runBlocking(Dispatchers.IO) {
                        dataStore.edit { preferences ->
                            legacyModels.forEach { (provider, model) ->
                                val key = preferenceKey(provider)
                                if (preferences[key]?.trim().isNullOrBlank()) {
                                    preferences[key] = model
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Best effort. The next read retries, and the legacy values
                    // remain available in encrypted preferences.
                }
            }
            legacyMigrationComplete = true
        }
    }

    @Suppress("DEPRECATION")
    private fun readLegacyModels(): Map<AiProvider, String> = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val securePreferences = EncryptedSharedPreferences.create(
            appContext,
            LEGACY_SECURE_PREFERENCES_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        buildMap {
            AiProvider.entries.forEach { provider ->
                securePreferences.getString("ai_${provider.name.lowercase()}_model", null)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { model -> put(provider, model) }
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun preferenceKey(provider: AiProvider) = when (provider) {
        AiProvider.OPENROUTER -> SELECTED_OPENROUTER_MODEL
        AiProvider.OPENCODE -> SELECTED_OPENCODE_MODEL
        AiProvider.GEMINI -> SELECTED_GEMINI_MODEL
        AiProvider.OPENAI -> SELECTED_OPENAI_MODEL
        AiProvider.ANTHROPIC -> SELECTED_ANTHROPIC_MODEL
        AiProvider.GROQ -> SELECTED_GROQ_MODEL
        AiProvider.DEEPSEEK -> SELECTED_DEEPSEEK_MODEL
    }

    companion object {
        // Exact keys required for independent per-provider model persistence.
        val SELECTED_OPENROUTER_MODEL = stringPreferencesKey("SELECTED_OPENROUTER_MODEL")
        val SELECTED_OPENCODE_MODEL = stringPreferencesKey("SELECTED_OPENCODE_MODEL")
        val SELECTED_GEMINI_MODEL = stringPreferencesKey("SELECTED_GEMINI_MODEL")

        private val SELECTED_OPENAI_MODEL = stringPreferencesKey("SELECTED_OPENAI_MODEL")
        private val SELECTED_ANTHROPIC_MODEL = stringPreferencesKey("SELECTED_ANTHROPIC_MODEL")
        private val SELECTED_GROQ_MODEL = stringPreferencesKey("SELECTED_GROQ_MODEL")
        private val SELECTED_DEEPSEEK_MODEL = stringPreferencesKey("SELECTED_DEEPSEEK_MODEL")

        private const val LEGACY_SECURE_PREFERENCES_FILE = "vibereader_secure_preferences"

        @Volatile
        private var legacyMigrationComplete = false
        private val legacyMigrationLock = Any()
    }
}

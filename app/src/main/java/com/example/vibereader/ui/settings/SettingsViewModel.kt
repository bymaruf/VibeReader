package com.example.vibereader.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.core.theme.ThemeOption
import com.example.vibereader.data.local.AiKeyVault
import com.example.vibereader.data.remote.DiscoveredModel
import com.example.vibereader.data.remote.ModelDiscoveryException
import com.example.vibereader.data.repository.AiRuntimeResolver
import com.example.vibereader.data.repository.ActiveAiConfig
import com.example.vibereader.data.room.AppThemePreferenceEntity
import com.example.vibereader.model.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AiConnectionStatus {
    NO_KEY,
    NOT_CHECKED,
    CHECKING,
    CONNECTED,
    INVALID_KEY,
    UNREACHABLE,
}

data class ModelDiscoveryState(
    val provider: AiProvider? = null,
    val models: List<DiscoveredModel> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val selectedModel: String? = null,
    val status: AiConnectionStatus = AiConnectionStatus.NOT_CHECKED,
)

class SettingsViewModel(
    private val database: VibeReaderDatabase,
    context: Context,
) : ViewModel() {
    private val appContext = context.applicationContext

    // Safe, non-null StateFlows with initial defaults so UI never reads
    // uninitialized state. setTheme() updates _appTheme synchronously, which
    // makes the activity-level VibeReaderTheme recompose on the same frame as
    // the tap; persistence happens afterwards in the background.
    private val _appTheme = MutableStateFlow(ThemeOption.SYSTEM)
    val appTheme: StateFlow<ThemeOption> = _appTheme.asStateFlow()

    private val _themeError = MutableStateFlow<String?>(null)
    val themeError: StateFlow<String?> = _themeError.asStateFlow()

    private val _modelDiscovery = MutableStateFlow(ModelDiscoveryState())
    val modelDiscovery: StateFlow<ModelDiscoveryState> = _modelDiscovery.asStateFlow()

    /**
     * Cached saved model IDs per provider, loaded on IO. This avoids blocking
     * DataStore reads on the main thread when Compose reads saved model state.
     * Missing entries mean "no explicit selection yet" (null).
     */
    private val _savedModels = MutableStateFlow<Map<AiProvider, String>>(emptyMap())
    val savedModels: StateFlow<Map<AiProvider, String>> = _savedModels.asStateFlow()

    private var hasUserSelectedTheme = false
    private var modelDiscoveryJob: Job? = null
    private var themeSaveJob: Job? = null

    // Defer/guard vault initialization: encrypted prefs or DataStore-backed model
    // storage can throw on corrupted devices; a crash here must not take down the app.
    private val keyVault: AiKeyVault? by lazy {
        runCatching { AiKeyVault(appContext) }
            .onFailure { Log.w(TAG, "AI key vault unavailable; settings will use safe defaults.", it) }
            .getOrNull()
    }

    init {
        // Eagerly load saved models on IO so UI state has defaults immediately and
        // never performs blocking DataStore reads on the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            refreshSavedModels()
        }

        viewModelScope.launch {
            runCatching {
                val provider = withContext(Dispatchers.IO) {
                    keyVault?.getSelectedProvider() ?: AiProvider.OPENROUTER
                }
                val apiKey = withContext(Dispatchers.IO) {
                    keyVault?.getApiKey(provider).orEmpty()
                }
                discoverModels(provider, apiKey)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Could not initialize AI model settings.", error)
                _modelDiscovery.value = ModelDiscoveryState(
                    message = "AI settings could not be loaded. You can still configure providers.",
                    status = AiConnectionStatus.NOT_CHECKED,
                )
            }
        }
        viewModelScope.launch {
            try {
                val savedTheme = withContext(Dispatchers.IO) {
                    database.appThemePreferenceDao()
                        .getById()
                        ?.themeOption
                }
                // Never let the asynchronous initial read overwrite a selection
                // the user already made while the database call was in flight.
                if (!hasUserSelectedTheme) {
                    _appTheme.value = ThemeOption.entries.firstOrNull { it.name == savedTheme }
                        ?: ThemeOption.SYSTEM
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not load the saved app theme.", error)
                _themeError.value = error.localizedMessage ?: "Could not load the saved app theme."
                if (!hasUserSelectedTheme) {
                    _appTheme.value = ThemeOption.SYSTEM
                }
            }
        }
    }

    /**
     * Centralized active AI config resolver (IO-safe). Validates the active
     * provider's API key and dedicated saved model; throws setup error when no
     * usable provider/key/model exists.
     */
    suspend fun getActiveAiConfig(): ActiveAiConfig? = withContext(Dispatchers.IO) {
        val vault = keyVault ?: return@withContext null
        runCatching { AiRuntimeResolver.getActiveAiConfig(vault) }.getOrNull()
    }

    /**
     * Returns the cached persisted model for [provider]. This is main-thread
     * safe and never performs blocking DataStore reads.
     */
    fun savedModelFor(provider: AiProvider): String? = _savedModels.value[provider]

    fun refreshSavedModel(provider: AiProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            val model = runCatching {
                keyVault?.getAiModel(provider)?.trim()?.takeIf(String::isNotBlank)
            }.getOrNull()
            _savedModels.update { current ->
                if (model == null) current - provider else current + (provider to model)
            }
        }
    }

    private fun refreshSavedModels() {
        val vault = keyVault ?: return
        val models = buildMap {
            AiProvider.entries.forEach { provider ->
                runCatching {
                    vault.getAiModel(provider)?.trim()?.takeIf(String::isNotBlank)
                }.getOrNull()?.let { put(provider, it) }
            }
        }
        _savedModels.value = models
    }

    /**
     * Persists an explicit user selection immediately under this provider's
     * dedicated DataStore key and mirrors it into the discovery/cache state.
     *
     * This method never touches API keys, provider enablement, selected-provider
     * routing or failover/rotation.
     */
    fun selectModel(provider: AiProvider, model: String) {
        val normalized = model.trim()
        if (normalized.isBlank()) return
        _savedModels.update { it + (provider to normalized) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                keyVault?.saveAiModel(provider, normalized)
            }.onFailure { error ->
                Log.w(TAG, "Could not persist selected model for ${provider.name}.", error)
            }
            refreshSavedModel(provider)
        }
        _modelDiscovery.update { state ->
            if (state.provider == provider) {
                state.copy(selectedModel = normalized)
            } else {
                state
            }
        }
    }

    fun discoverModels(provider: AiProvider, apiKey: String) {
        modelDiscoveryJob?.cancel()
        modelDiscoveryJob = viewModelScope.launch {
            val vault = keyVault
            val persistedModel = vault?.let { readSavedModel(it, provider) } ?: savedModelFor(provider)

            if (vault == null) {
                _modelDiscovery.value = ModelDiscoveryState(
                    provider = provider,
                    selectedModel = persistedModel,
                    message = "Secure storage is unavailable on this device. AI settings cannot be loaded.",
                    status = AiConnectionStatus.UNREACHABLE,
                )
                return@launch
            }
            if (apiKey.isBlank()) {
                _modelDiscovery.value = ModelDiscoveryState(
                    provider = provider,
                    selectedModel = persistedModel,
                    message = "Add an API key to discover available models.",
                    status = AiConnectionStatus.NO_KEY,
                )
                return@launch
            }

            _modelDiscovery.value = ModelDiscoveryState(
                provider = provider,
                isLoading = true,
                message = "Checking key and loading models…",
                selectedModel = persistedModel,
                status = AiConnectionStatus.CHECKING,
            )
            try {
                val models = vault.discoverModels(provider, apiKey)

                // Read the exact persisted model for this provider after the
                // network call. The settings card persists explicit selections
                // immediately, so a model chosen while discovery was running is
                // already visible here. A saved non-empty value always wins.
                val latestSavedModel = readSavedModel(vault, provider) ?: persistedModel
                val selectedModel = if (latestSavedModel != null) {
                    latestSavedModel
                } else {
                    val optimalModel = runCatching {
                        vault.selectOptimalDefaultModel(models, provider)
                    }.getOrNull()
                    val fallback = optimalModel?.id
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: provider.defaultModel
                    // Atomic conditional write. If the user selected a model
                    // between the read above and this write, that explicit
                    // selection is kept and returned instead of being replaced.
                    withContext(Dispatchers.IO) {
                        runCatching {
                            vault.saveAiModelIfAbsent(provider, fallback)
                        }.getOrNull()
                    } ?: fallback
                }
                _savedModels.update { current ->
                    current + (provider to selectedModel)
                }

                val freeCount = models.count { runCatching { it.isFree }.getOrDefault(false) }
                _modelDiscovery.value = ModelDiscoveryState(
                    provider = provider,
                    models = models,
                    message = if (models.isEmpty()) {
                        "Key is valid, but no compatible chat models were returned. " +
                            "You can enter a model ID manually."
                    } else {
                        "Connected · ${models.size} models available" +
                            if (freeCount > 0) " ($freeCount free)" else ""
                    },
                    selectedModel = selectedModel,
                    status = AiConnectionStatus.CONNECTED,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: ModelDiscoveryException) {
                val invalidKey = runCatching { error.isInvalidKey }.getOrDefault(false)
                _modelDiscovery.value = ModelDiscoveryState(
                    provider = provider,
                    selectedModel = readSavedModel(vault, provider) ?: persistedModel ?: savedModelFor(provider),
                    message = if (invalidKey) {
                        "Invalid API key for ${provider.displayName}."
                    } else {
                        error.localizedMessage ?: "Model discovery failed."
                    },
                    status = if (invalidKey) {
                        AiConnectionStatus.INVALID_KEY
                    } else {
                        AiConnectionStatus.UNREACHABLE
                    },
                )
            } catch (error: Exception) {
                Log.w(TAG, "Model discovery failed for ${provider.name}.", error)
                _modelDiscovery.value = ModelDiscoveryState(
                    provider = provider,
                    selectedModel = readSavedModel(vault, provider) ?: persistedModel ?: savedModelFor(provider),
                    message = "Model discovery failed: " +
                        (error.localizedMessage ?: "Check the network connection."),
                    status = AiConnectionStatus.UNREACHABLE,
                )
            }
        }
    }

    fun selectOptimalDefaultModel(
        models: List<DiscoveredModel>,
        provider: AiProvider,
    ): DiscoveredModel? = runCatching {
        keyVault?.selectOptimalDefaultModel(models, provider)
    }.getOrNull()

    /**
     * Applies the selected app appearance immediately, then persists it.
     *
     * The theme StateFlow is updated synchronously so the activity-scoped
     * VibeReaderTheme recomposes on the next frame. A newer tap cancels the
     * previous save job, which keeps the last selection as the persisted one.
     */
    fun setTheme(option: ThemeOption) {
        hasUserSelectedTheme = true
        val previousTheme = _appTheme.value
        if (previousTheme == option) {
            _themeError.value = null
            return
        }
        _appTheme.value = option
        _themeError.value = null

        themeSaveJob?.cancel()
        themeSaveJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.appThemePreferenceDao().upsert(
                        AppThemePreferenceEntity(themeOption = option.name),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not save the selected app theme.", error)
                // Only roll back if this is still the active selection; a
                // newer tap must not be overwritten by an older failed save.
                if (_appTheme.value == option) {
                    _appTheme.value = previousTheme
                    _themeError.value =
                        error.localizedMessage ?: "Could not save the selected app theme."
                }
            }
        }
    }

    private suspend fun readSavedModel(vault: AiKeyVault, provider: AiProvider): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                vault.getAiModel(provider)?.trim()?.takeIf(String::isNotBlank)
            }.getOrNull()
        }

    class Factory(
        private val database: VibeReaderDatabase,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                throw IllegalArgumentException("Unsupported ViewModel type: ${modelClass.name}")
            }
            return SettingsViewModel(database, context.applicationContext) as T
        }
    }

    private companion object {
        private const val TAG = "SettingsViewModel"
    }
}

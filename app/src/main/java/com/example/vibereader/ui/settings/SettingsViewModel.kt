package com.example.vibereader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.core.secure_storage.ApiKeyStore
import com.example.vibereader.core.theme.ThemeOption
import com.example.vibereader.data.remote.DiscoveredModel
import com.example.vibereader.data.remote.ModelDiscoveryService
import com.example.vibereader.data.room.AppThemePreferenceEntity
import com.example.vibereader.model.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelDiscoveryState(
    val provider: AiProvider? = null,
    val models: List<DiscoveredModel> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val selectedModel: String? = null,
)

class SettingsViewModel(
    private val database: VibeReaderDatabase,
    context: Context,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val apiKeyStore = ApiKeyStore(appContext)
    private val modelDiscoveryService = ModelDiscoveryService()
    private val _appTheme = MutableStateFlow(ThemeOption.SYSTEM)
    val appTheme: StateFlow<ThemeOption> = _appTheme.asStateFlow()
    private val _themeError = MutableStateFlow<String?>(null)
    val themeError: StateFlow<String?> = _themeError.asStateFlow()
    private val _modelDiscovery = MutableStateFlow(ModelDiscoveryState())
    val modelDiscovery: StateFlow<ModelDiscoveryState> = _modelDiscovery.asStateFlow()
    private var hasUserSelectedTheme = false
    private var modelDiscoveryJob: Job? = null

    init {
        val provider = apiKeyStore.getSelectedAiProvider()
        val apiKey = apiKeyStore.getApiKey(provider).orEmpty()
        discoverModels(provider, apiKey)
        viewModelScope.launch {
            try {
                val savedTheme = withContext(Dispatchers.IO) {
                    database.appThemePreferenceDao()
                        .getById()
                        ?.themeOption
                }
                if (!hasUserSelectedTheme) {
                    _appTheme.value = ThemeOption.entries.firstOrNull { it.name == savedTheme }
                        ?: ThemeOption.SYSTEM
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _themeError.value = error.localizedMessage ?: "Could not load the saved app theme."
            }
        }
    }

    fun discoverModels(provider: AiProvider, apiKey: String) {
        modelDiscoveryJob?.cancel()
        if (apiKey.isBlank()) {
            _modelDiscovery.value = ModelDiscoveryState(
                provider = provider,
                message = "Add an API key to discover available models.",
            )
            return
        }

        modelDiscoveryJob = viewModelScope.launch {
            _modelDiscovery.value = ModelDiscoveryState(
                provider = provider,
                isLoading = true,
                message = "Loading models…",
            )
            try {
                val models = modelDiscoveryService.fetchAvailableModels(provider, apiKey)
                val savedModel = withContext(Dispatchers.IO) {
                    apiKeyStore.getAiModel(provider)
                }
                val optimalModel = selectOptimalDefaultModel(models, provider)
                val selectedModel = if (
                    savedModel.isNullOrBlank() || savedModel == provider.defaultModel
                ) {
                    optimalModel?.id ?: savedModel ?: provider.defaultModel
                } else {
                    savedModel
                }
                if (selectedModel != savedModel && !selectedModel.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        apiKeyStore.saveAiModel(provider, selectedModel)
                    }
                }
                _modelDiscovery.value = ModelDiscoveryState(
                    provider = provider,
                    models = models,
                    message = if (models.isEmpty()) {
                        "No compatible chat models were returned. You can enter a model ID manually."
                    } else {
                        "Found ${models.size} available models."
                    },
                    selectedModel = selectedModel,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _modelDiscovery.value = ModelDiscoveryState(
                    provider = provider,
                    message = "Model discovery failed: " +
                        (error.localizedMessage ?: "Check the key and network connection."),
                )
            }
        }
    }

    fun selectOptimalDefaultModel(
        models: List<DiscoveredModel>,
        provider: AiProvider,
    ): DiscoveredModel? {
        val bestModel = when (provider) {
            AiProvider.OPENROUTER -> models.firstOrNull {
                !it.isFreeTier && it.id.contains("claude-3-5", ignoreCase = true)
            } ?: models.firstOrNull {
                !it.isFreeTier && it.id.contains("gpt-4o-mini", ignoreCase = true)
            } ?: models.firstOrNull {
                it.id.contains("claude-3-5", ignoreCase = true) ||
                    it.id.contains("gpt-4o-mini", ignoreCase = true)
            }
            AiProvider.OPENAI -> models.firstOrNull {
                it.id.contains("gpt-4o-mini", ignoreCase = true)
            }
            AiProvider.GEMINI,
            AiProvider.GROQ,
            AiProvider.DEEPSEEK,
            -> models.firstOrNull(DiscoveredModel::isFreeTier)
        } ?: models.firstOrNull()
        return bestModel
    }

    fun setTheme(option: ThemeOption) {
        hasUserSelectedTheme = true
        val previousTheme = _appTheme.value
        _appTheme.value = option
        _themeError.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.appThemePreferenceDao().upsert(
                        AppThemePreferenceEntity(themeOption = option.name),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _appTheme.value = previousTheme
                _themeError.value = error.localizedMessage ?: "Could not save the selected app theme."
            }
        }
    }

    class Factory(
        private val database: VibeReaderDatabase,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                throw IllegalArgumentException("Unsupported ViewModel type: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(database, context) as T
        }
    }
}

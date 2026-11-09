package com.example.vibereader.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vibereader.core.secure_storage.ApiKeyStore
import com.example.vibereader.model.AiProvider
import com.example.vibereader.model.ProviderKeyConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FreeGreen = Color(0xFF2E9E5B)
private val ProIndigo = Color(0xFF5B5BD6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeySettingsCard(
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val apiKeyStore = remember(context.applicationContext) {
        runCatching { ApiKeyStore(context.applicationContext) }.getOrNull()
    }
    val discovery by settingsViewModel.modelDiscovery.collectAsStateWithLifecycle(
        initialValue = ModelDiscoveryState(),
    )
    val savedModels by settingsViewModel.savedModels.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )
    val coroutineScope = rememberCoroutineScope()

    var selectedProvider by remember(apiKeyStore) {
        mutableStateOf(
            runCatching { apiKeyStore?.getSelectedAiProvider() }.getOrNull()
                ?: AiProvider.OPENROUTER,
        )
    }
    var apiKey by remember(apiKeyStore, selectedProvider) {
        mutableStateOf(
            runCatching { apiKeyStore?.getApiKey(selectedProvider).orEmpty() }.getOrDefault(""),
        )
    }
    var isEditingKey by remember(apiKeyStore, selectedProvider) {
        mutableStateOf(
            runCatching { apiKeyStore?.getApiKey(selectedProvider).isNullOrBlank() }
                .getOrDefault(true),
        )
    }
    var persistedModel by remember(apiKeyStore, selectedProvider, savedModels[selectedProvider]) {
        mutableStateOf(savedModels[selectedProvider])
    }
    var selectedModel by remember(apiKeyStore, selectedProvider, savedModels[selectedProvider]) {
        mutableStateOf(savedModels[selectedProvider] ?: selectedProvider.defaultModel)
    }
    var modelDirty by remember(selectedProvider) { mutableStateOf(false) }
    var fallbackEnabled by remember(apiKeyStore) {
        mutableStateOf(
            AiProvider.entries.associateWith { provider ->
                runCatching { apiKeyStore?.getProviderConfig(provider)?.isEnabled ?: true }
                    .getOrDefault(true)
            },
        )
    }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var isSavingKey by remember { mutableStateOf(false) }
    var isSavingSettings by remember { mutableStateOf(false) }
    var storageVersion by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    val configuredProviders = remember(apiKeyStore, storageVersion) {
        AiProvider.entries.filter { provider ->
            runCatching { apiKeyStore?.isConfigured(provider) == true }.getOrDefault(false)
        }.toSet()
    }
    val storedKey = remember(apiKeyStore, selectedProvider, storageVersion) {
        runCatching { apiKeyStore?.getApiKey(selectedProvider).orEmpty() }.getOrDefault("")
    }
    val activeDiscovery = discovery.takeIf { it.provider == selectedProvider }
    val availableModels = activeDiscovery?.models.orEmpty()
    val isBusy = isSavingKey || isSavingSettings
    val isKeyDirty = isEditingKey && apiKey.trim() != storedKey
    val status = when {
        activeDiscovery != null && !isKeyDirty -> activeDiscovery.status
        isKeyDirty -> AiConnectionStatus.NOT_CHECKED
        storedKey.isBlank() -> AiConnectionStatus.NO_KEY
        else -> AiConnectionStatus.NOT_CHECKED
    }
    val selectedModelInfo = availableModels.firstOrNull {
        it.id.equals(selectedModel.trim(), ignoreCase = true)
    }

    // Persists an explicit selection immediately under the provider's dedicated
    // DataStore key. Failover/API-key storage is not touched.
    fun persistModelSelection(provider: AiProvider, model: String) {
        val normalized = model.trim()
        if (normalized.isBlank()) return
        settingsViewModel.selectModel(provider, normalized)
        if (provider == selectedProvider) {
            persistedModel = normalized
        }
    }

    // A saved non-empty model always wins over discovery defaults/fallbacks.
    // Discovery may only supply the initial value when nothing was saved yet.
    LaunchedEffect(
        activeDiscovery?.provider,
        activeDiscovery?.isLoading,
        activeDiscovery?.selectedModel,
        activeDiscovery?.status,
        savedModels[selectedProvider],
    ) {
        val state = activeDiscovery ?: return@LaunchedEffect
        val saved = savedModels[selectedProvider]
        if (saved != null) {
            persistedModel = saved
            selectedModel = saved
            modelDirty = false
            return@LaunchedEffect
        }

        // No saved selection yet. Use discovery's selected model only as an
        // initial/default suggestion and never after a manual edit.
        val discoveryModel = state.selectedModel?.trim()?.takeIf(String::isNotBlank)
        if (
            !state.isLoading &&
            !modelDirty &&
            discoveryModel != null &&
            state.status == AiConnectionStatus.CONNECTED
        ) {
            selectedModel = discoveryModel
        }
    }

    val latestProvider by rememberUpdatedState(selectedProvider)
    val latestSelectedModel by rememberUpdatedState(selectedModel)
    val latestPersistedModel by rememberUpdatedState(persistedModel)
    val latestModelDirty by rememberUpdatedState(modelDirty)

    DisposableEffect(Unit) {
        onDispose {
            // Save a pending manual model entry when leaving the screen. Never
            // overwrite an existing saved model with a blank/default value.
            val pending = latestSelectedModel.trim()
            val saved = latestPersistedModel?.trim()?.takeIf(String::isNotBlank)
            if (latestModelDirty && pending.isNotBlank() && pending != saved) {
                persistModelSelection(latestProvider, pending)
            }
        }
    }

    fun selectProvider(provider: AiProvider) {
        if (provider == selectedProvider) return

        // Flush a manually typed model before leaving the current provider.
        val pendingModel = selectedModel.trim()
        if (modelDirty && pendingModel.isNotBlank()) {
            settingsViewModel.selectModel(selectedProvider, pendingModel)
        }

        selectedProvider = provider
        val savedKey = runCatching { apiKeyStore?.getApiKey(provider).orEmpty() }.getOrDefault("")
        apiKey = savedKey
        isEditingKey = savedKey.isBlank()

        // Load this provider's own persisted model from the IO-loaded cache.
        // It is never replaced by a default while the provider tab is open.
        val savedModel = savedModels[provider]
        persistedModel = savedModel
        selectedModel = savedModel ?: provider.defaultModel
        modelDirty = false
        modelMenuExpanded = false
        message = null
        settingsViewModel.discoverModels(provider, savedKey)
    }

    fun saveKey() {
        val keyToSave = apiKey.trim()
        val providerToSave = selectedProvider
        isSavingKey = true
        message = null
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (keyToSave.isBlank()) {
                        apiKeyStore?.clearApiKey(providerToSave)
                    } else {
                        apiKeyStore?.saveApiKey(providerToSave, keyToSave)
                    }
                }
                apiKey = keyToSave
                isEditingKey = keyToSave.isBlank()
                storageVersion++
                message = if (keyToSave.isBlank()) {
                    "API key removed."
                } else {
                    "API key saved securely."
                }
                settingsViewModel.discoverModels(providerToSave, keyToSave)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "Could not save API key: " +
                    (error.localizedMessage ?: "secure storage error")
            } finally {
                isSavingKey = false
            }
        }
    }

    fun saveSettings() {
        val providerToSave = selectedProvider
        val keyToSave = apiKey.trim()
        // A blank field must never erase an existing explicit selection.
        val savedBeforeSave = savedModels[providerToSave]
        val modelToSave = selectedModel.trim().ifBlank {
            savedBeforeSave ?: providerToSave.defaultModel
        }
        val fallbackSettings = fallbackEnabled
        isSavingSettings = true
        message = null
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Key pool / failover configuration is unchanged.
                    apiKeyStore?.saveProviderConfig(
                        ProviderKeyConfig(
                            provider = providerToSave,
                            apiKey = keyToSave,
                            isEnabled = true,
                        ),
                    )
                    apiKeyStore?.setSelectedAiProvider(providerToSave)

                    // Persist the explicit model immediately under this
                    // provider's dedicated key before discovery runs.
                    settingsViewModel.selectModel(providerToSave, modelToSave)

                    fallbackSettings.forEach { (provider, enabled) ->
                        apiKeyStore?.setProviderEnabled(provider, enabled)
                    }
                }
                selectedModel = modelToSave
                persistedModel = modelToSave
                modelDirty = false
                isEditingKey = keyToSave.isBlank()
                storageVersion++
                message = "Provider and fallback settings saved."
                settingsViewModel.discoverModels(providerToSave, keyToSave)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "Could not save AI settings: " +
                    (error.localizedMessage ?: "storage error")
            } finally {
                isSavingSettings = false
            }
        }
    }

    SettingsCard("AI PROVIDERS", modifier = modifier) {
        Text(
            "Connect one or more providers. VibeReader discovers each provider's live " +
                "model list and automatically fails over when a key is rejected or a " +
                "provider is rate-limited.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AiProvider.entries.forEach { provider ->
                val selected = provider == selectedProvider
                val availabilityColor = when {
                    selected && status == AiConnectionStatus.CONNECTED -> FreeGreen
                    selected && status == AiConnectionStatus.INVALID_KEY ->
                        MaterialTheme.colorScheme.error

                    provider in configuredProviders -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                }
                Surface(
                    onClick = { selectProvider(provider) },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(50),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(availabilityColor, CircleShape),
                        )
                        Text(
                            text = provider.shortLabel,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedProvider.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = selectedProvider.baseUrl
                        .removePrefix("https://")
                        .trimEnd('/'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ConnectionStatusPill(status)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("API key", style = MaterialTheme.typography.labelMedium)
                TextButton(
                    enabled = !isBusy,
                    onClick = {
                        if (isEditingKey) {
                            saveKey()
                        } else {
                            isEditingKey = true
                            message = null
                        }
                    },
                ) {
                    Text(
                        when {
                            isSavingKey -> "Saving…"
                            isEditingKey -> "Save Key"
                            else -> "Edit"
                        },
                    )
                }
            }
            OutlinedTextField(
                value = if (!isEditingKey && apiKey.isNotEmpty()) {
                    "••••••••••••••••"
                } else {
                    apiKey
                },
                onValueChange = { newValue ->
                    if (isEditingKey) {
                        apiKey = newValue
                        message = null
                    }
                },
                enabled = isEditingKey && !isBusy,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                placeholder = { Text(selectedProvider.keyHint) },
                singleLine = true,
                visualTransformation = if (isEditingKey || apiKey.isEmpty()) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                supportingText = {
                    Text(
                        if (isEditingKey) {
                            "Save Key to validate it and load the model catalog. " +
                                "Saving an empty value removes this key."
                        } else {
                            "Key stored in EncryptedSharedPreferences on this device."
                        },
                    )
                },
            )
        }

        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded,
            onExpandedChange = { expanded ->
                modelMenuExpanded = expanded && !isSavingSettings
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selectedModel,
                onValueChange = { newValue ->
                    selectedModel = newValue
                    modelDirty = true
                    message = null
                },
                enabled = !isSavingSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(
                        type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                        enabled = !isSavingSettings,
                    ),
                label = { Text("Model") },
                singleLine = true,
                trailingIcon = {
                    if (activeDiscovery?.isLoading == true) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                    }
                },
                supportingText = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selectedModelInfo?.name
                                ?: "Pick a discovered model or type an ID manually.",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        selectedModelInfo?.let { model ->
                            ModelTierBadge(isFree = model.isFree, isPro = model.isPro)
                        }
                    }
                },
                placeholder = { Text(selectedProvider.defaultModel) },
            )
            ExposedDropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                if (availableModels.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (activeDiscovery?.isLoading == true) {
                                    "Loading models…"
                                } else {
                                    "No models discovered yet. Enter a model ID manually."
                                },
                            )
                        },
                        onClick = { modelMenuExpanded = false },
                        enabled = false,
                    )
                } else {
                    availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 12.dp),
                                    ) {
                                        Text(
                                            text = model.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = model.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    ModelTierBadge(
                                        isFree = model.isFree,
                                        isPro = model.isPro,
                                    )
                                }
                            },
                            onClick = {
                                selectedModel = model.id
                                modelDirty = true
                                modelMenuExpanded = false
                                message = null
                                // Save under this provider's own key immediately,
                                // so switching provider tabs cannot lose it.
                                persistModelSelection(selectedProvider, model.id)
                            },
                        )
                    }
                }
            }
        }

        if (!isKeyDirty) {
            activeDiscovery?.let { state ->
                state.message?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (state.status) {
                            AiConnectionStatus.INVALID_KEY,
                            AiConnectionStatus.UNREACHABLE,
                            -> MaterialTheme.colorScheme.error

                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        if (selectedProvider == AiProvider.OPENCODE) {
            Text(
                "OpenCode labels zero-cost models FREE and paid models PAID/PRO. " +
                    "Some opencode.ai free-tier chat models are currently limited to the " +
                    "OpenCode client; paid models and Go subscriptions work from any client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        )

        Text("FALLBACK PROVIDERS", style = MaterialTheme.typography.labelLarge)
        Text(
            "Failover order follows this list. Providers are skipped when they have no key, " +
                "are disabled, or reject the request.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AiProvider.entries.filter { it != selectedProvider }.forEach { provider ->
            val configured = provider in configuredProviders
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (configured) {
                            "Available as a backup provider"
                        } else {
                            "Add a key in its provider tab to enable failover"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = fallbackEnabled[provider] ?: true,
                    enabled = configured && !isSavingSettings,
                    onCheckedChange = { enabled ->
                        fallbackEnabled = fallbackEnabled + (provider to enabled)
                    },
                )
            }
        }
        Button(
            onClick = { saveSettings() },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    isSavingSettings -> "Saving AI settings…"
                    activeDiscovery?.isLoading == true -> "Validating key…"
                    else -> "Save AI settings"
                },
            )
        }
        message?.let { text ->
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ConnectionStatusPill(status: AiConnectionStatus) {
    val label = when (status) {
        AiConnectionStatus.NO_KEY -> "[ No Key ]"
        AiConnectionStatus.NOT_CHECKED -> "[ Not Verified ]"
        AiConnectionStatus.CHECKING -> "[ Checking… ]"
        AiConnectionStatus.CONNECTED -> "[ ✓ Connected ]"
        AiConnectionStatus.INVALID_KEY -> "[ ⚠ Invalid Key ]"
        AiConnectionStatus.UNREACHABLE -> "[ ⚠ Unreachable ]"
    }
    val containerColor = when (status) {
        AiConnectionStatus.CONNECTED -> FreeGreen.copy(alpha = 0.16f)
        AiConnectionStatus.INVALID_KEY,
        AiConnectionStatus.UNREACHABLE,
        -> MaterialTheme.colorScheme.errorContainer

        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when (status) {
        AiConnectionStatus.CONNECTED -> FreeGreen
        AiConnectionStatus.INVALID_KEY,
        AiConnectionStatus.UNREACHABLE,
        -> MaterialTheme.colorScheme.onErrorContainer

        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.35f)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun ModelTierBadge(isFree: Boolean, isPro: Boolean) {
    Surface(
        color = if (isFree) FreeGreen else ProIndigo,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = when {
                isFree -> "FREE"
                isPro -> "PRO"
                else -> "PAID"
            },
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

package com.example.vibereader.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.core.secure_storage.ApiKeyStore
import com.example.vibereader.core.theme.ThemeOption
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.local.WallpaperTarget
import com.example.vibereader.data.remote.DiscoveredModel
import com.example.vibereader.model.AiProvider
import com.example.vibereader.model.ProviderKeyConfig
import com.example.vibereader.data.repository.BookParserRepository
import com.example.vibereader.ui.library.LibraryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val database = remember(context) { VibeReaderDatabase.getInstance(context) }
    val libraryViewModel: LibraryViewModel = viewModel(
        factory = remember(context, database) {
            LibraryViewModel.Factory(context.applicationContext, database)
        },
    )
    val scanState by libraryViewModel.scanState.collectAsState()
    val appTheme by settingsViewModel.appTheme.collectAsStateWithLifecycle()
    val themeError by settingsViewModel.themeError.collectAsStateWithLifecycle()
    val parserRepository = remember(context, database) {
        BookParserRepository(context.applicationContext, database)
    }
    val apiKeyStore = remember(context) { ApiKeyStore(context.applicationContext) }
    val modelDiscovery by settingsViewModel.modelDiscovery.collectAsStateWithLifecycle()
    val wallpaperPreferences = remember(context) { WallpaperPreferences(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    var aiProvider by remember(apiKeyStore) { mutableStateOf(apiKeyStore.getSelectedAiProvider()) }
    var apiKey by remember(apiKeyStore, aiProvider) {
        mutableStateOf(apiKeyStore.getApiKey(aiProvider).orEmpty())
    }
    var isEditingKey by remember(apiKeyStore, aiProvider) {
        mutableStateOf(apiKeyStore.getApiKey(aiProvider).isNullOrBlank())
    }
    var aiModel by remember(apiKeyStore, aiProvider) {
        mutableStateOf(apiKeyStore.getAiModel(aiProvider) ?: aiProvider.defaultModel)
    }
    val discoveredModels = if (modelDiscovery.provider == aiProvider) {
        modelDiscovery.models
    } else {
        emptyList()
    }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var isSavingApiSettings by remember { mutableStateOf(false) }
    val isDiscoveringModels = modelDiscovery.isLoading || isSavingApiSettings
    val modelDiscoveryMessage = modelDiscovery.takeIf { it.provider == aiProvider }?.message
    var fallbackEnabled by remember(apiKeyStore) {
        mutableStateOf(
            AiProvider.entries.associateWith { provider ->
                apiKeyStore.getProviderConfig(provider).isEnabled
            },
        )
    }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var showImportOptions by remember { mutableStateOf(false) }
    var wallpaperSettings by remember(wallpaperPreferences) {
        mutableStateOf(wallpaperPreferences.getSettings())
    }
    LaunchedEffect(aiProvider, modelDiscovery.selectedModel, modelDiscovery.provider) {
        if (modelDiscovery.provider == aiProvider) {
            modelDiscovery.selectedModel?.takeIf(String::isNotBlank)?.let { aiModel = it }
        }
    }
    var wallpaperTargetMenuExpanded by remember { mutableStateOf(false) }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isImporting = true
                message = null
                try {
                    parserRepository.importBook(uri)
                    message = "Book imported and ready to read."
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    message = error.localizedMessage ?: "Could not import this book."
                } finally {
                    isImporting = false
                }
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                libraryViewModel.saveFolderAccess(uri)
                message = "Folder access saved. Scanning the selected folder."
            } catch (error: SecurityException) {
                message = error.localizedMessage ?: "Folder access could not be saved."
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SETTINGS", style = MaterialTheme.typography.headlineSmall)
        StorageSettingsCard(
            isImporting = isImporting,
            isScanning = scanState.isScanning,
            isProcessingMetadata = scanState.isProcessingMetadata,
            statusMessage = scanState.statusMessage,
            errorMessage = scanState.errorMessage,
            onImportBooksClicked = { showImportOptions = true },
            onRescanClicked = libraryViewModel::scanDeviceForBooks,
        )

        SettingsCard("APPEARANCE & THEME") {
            Text(
                "Choose the app appearance. Reading canvas themes are set in Reading Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeOption.entries.forEach { option ->
                    val label = when (option) {
                        ThemeOption.SYSTEM -> "SYSTEM"
                        ThemeOption.OLED_BLACK -> "OLED NIGHT"
                        ThemeOption.SEPIA -> "SEPIA"
                        ThemeOption.LIGHT -> "LIGHT"
                    }
                    val selected = appTheme == option
                    val pillBackground by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        label = "theme-pill-background",
                    )
                    val pillForeground by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "theme-pill-foreground",
                    )
                    Surface(
                        onClick = { settingsViewModel.setTheme(option) },
                        shape = RoundedCornerShape(50),
                        color = pillBackground,
                        contentColor = pillForeground,
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                        ),
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }

                }
            }
            themeError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SettingsCard("AI") {
            Text("Wallpaper target", style = MaterialTheme.typography.labelMedium)
            Box {
                Button(onClick = { wallpaperTargetMenuExpanded = true }) {
                    Text("Apply wallpaper to: ${wallpaperSettings.applyTarget.label}")
                }
                DropdownMenu(
                    expanded = wallpaperTargetMenuExpanded,
                    onDismissRequest = { wallpaperTargetMenuExpanded = false },
                ) {
                    WallpaperTarget.entries.forEach { target ->
                        DropdownMenuItem(
                            text = { Text(target.label) },
                            onClick = {
                                wallpaperSettings = wallpaperSettings.copy(applyTarget = target)
                                wallpaperPreferences.saveSettings(wallpaperSettings)
                                wallpaperTargetMenuExpanded = false
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Primary provider", style = MaterialTheme.typography.labelMedium)
                    Text(
                        aiProvider.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    Button(
                        onClick = { providerMenuExpanded = true },
                        enabled = !isDiscoveringModels && !isEditingKey,
                    ) {
                        Text("Change")
                    }
                DropdownMenu(
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false },
                ) {
                    AiProvider.entries.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.displayName) },
                            onClick = {
                                aiProvider = provider
                                apiKey = apiKeyStore.getApiKey(provider).orEmpty()
                                isEditingKey = apiKey.isBlank()
                                aiModel = apiKeyStore.getAiModel(provider) ?: provider.defaultModel
                                settingsViewModel.discoverModels(
                                    provider,
                                    apiKey,
                                )
                                fallbackEnabled = fallbackEnabled + (provider to true)
                                providerMenuExpanded = false
                                message = null
                            },
                        )
                    }
                }
                }
            }
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
                        enabled = !isDiscoveringModels,
                        onClick = {
                            if (!isEditingKey) {
                                isEditingKey = true
                            } else {
                                val keyToSave = apiKey.trim()
                                val providerToSave = aiProvider
                                isSavingApiSettings = true
                                coroutineScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            if (keyToSave.isBlank()) {
                                                apiKeyStore.clearApiKey(providerToSave)
                                            } else {
                                                apiKeyStore.saveApiKey(providerToSave, keyToSave)
                                            }
                                        }
                                        apiKey = keyToSave
                                        isEditingKey = false
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
                                        isSavingApiSettings = false
                                    }
                                }
                            }
                        },
                    ) {
                        Text(if (isEditingKey) "Save Key" else "Edit")
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
                    enabled = isEditingKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key") },
                    placeholder = { Text(aiProvider.keyHint) },
                    singleLine = true,
                    visualTransformation = if (isEditingKey) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    supportingText = {
                        Text(
                            if (isEditingKey) {
                                "Save Key to apply changes. Saving an empty value removes this key."
                            } else {
                                "Key locked and encrypted on this device."
                            },
                        )
                    },
                )
            }
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { expanded ->
                    modelMenuExpanded = expanded && !isDiscoveringModels && !isEditingKey
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = aiModel,
                    onValueChange = { aiModel = it; message = null },
                    enabled = !isDiscoveringModels && !isEditingKey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(
                            type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                            enabled = !isDiscoveringModels && !isEditingKey,
                        ),
                    label = { Text("Model ID") },
                    singleLine = true,
                    trailingIcon = {
                        if (isDiscoveringModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(12.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                        }
                    },
                    placeholder = { Text(aiProvider.defaultModel) },
                )
                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                ) {
                    val availableModels = discoveredModels.ifEmpty {
                        listOf(
                            DiscoveredModel(
                                id = aiProvider.defaultModel,
                                name = aiProvider.defaultModel,
                            ),
                        )
                    }
                    availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = model.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (model.id != model.name) {
                                        Text(
                                            text = model.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    val freeTier = model.isFreeTier
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (freeTier) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                    ) {
                                        Text(
                                            text = when {
                                                freeTier -> "[FREE]"
                                                model.isHighPrecision -> "[PAID / PRO]"
                                                else -> "[PAID]"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (freeTier) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            },
                                            modifier = Modifier.padding(
                                                horizontal = 8.dp,
                                                vertical = 3.dp,
                                            ),
                                        )
                                    }
                                }
                            },
                            onClick = {
                                aiModel = model.id
                                modelMenuExpanded = false
                            },
                        )
                    }
                }
            }
            modelDiscoveryMessage?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (discoveredModels.isEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text("BACKUP PROVIDERS", style = MaterialTheme.typography.labelLarge)
            AiProvider.entries.filter { it != aiProvider }.forEach { provider ->
                val configured = apiKeyStore.getApiKey(provider).isNullOrBlank().not()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(provider.displayName, style = MaterialTheme.typography.bodyMedium)
                        if (!configured) {
                            Text(
                                "Select provider above to add a key",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = fallbackEnabled[provider] ?: true,
                        enabled = configured,
                        onCheckedChange = { enabled ->
                            fallbackEnabled = fallbackEnabled + (provider to enabled)
                        },
                    )
                }
            }
            Button(
                onClick = {
                    if (isDiscoveringModels) return@Button
                    val providerToDiscover = aiProvider
                    val keyToDiscover = apiKey.trim()
                    val modelToSave = aiModel.trim().ifBlank { providerToDiscover.defaultModel }
                    val fallbackSettings = fallbackEnabled
                    isSavingApiSettings = true
                    coroutineScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                apiKeyStore.saveProviderConfig(
                                    ProviderKeyConfig(
                                        providerToDiscover,
                                        keyToDiscover,
                                        isEnabled = true,
                                    ),
                                )
                                apiKeyStore.setSelectedAiProvider(providerToDiscover)
                                apiKeyStore.saveAiModel(providerToDiscover, modelToSave)
                                fallbackSettings.forEach { (provider, enabled) ->
                                    apiKeyStore.setProviderEnabled(provider, enabled)
                                }
                            }
                            message = "Provider and fallback settings saved."
                            settingsViewModel.discoverModels(
                                providerToDiscover,
                                keyToDiscover,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            message = "Could not save AI settings: " +
                                (error.localizedMessage ?: "storage error")
                        } finally {
                            isSavingApiSettings = false
                        }
                    }
                },
                enabled = !isDiscoveringModels,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isDiscoveringModels) "Discovering models…" else "Save AI settings")
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    if (showImportOptions) {
        AlertDialog(
            onDismissRequest = { showImportOptions = false },
            title = { Text("Import books") },
            text = { Text("Choose books to import, or select a folder to scan.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportOptions = false
                        documentPicker.launch(arrayOf("application/pdf", "application/epub+zip"))
                    },
                ) {
                    Text("Choose files")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showImportOptions = false
                            folderPicker.launch(null)
                        },
                    ) {
                        Text("Choose folder")
                    }
                    TextButton(onClick = { showImportOptions = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

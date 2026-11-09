package com.example.vibereader.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.core.theme.ThemeOption
import com.example.vibereader.data.local.DictionaryPreferences
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.local.WallpaperSettings
import com.example.vibereader.data.local.WallpaperTarget
import com.example.vibereader.data.repository.BookParserRepository
import com.example.vibereader.ui.library.LibraryViewModel
import com.example.vibereader.util.AppDiscoveryHelper
import com.example.vibereader.util.AppInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = run {
        val context = LocalContext.current
        val database = remember(context) { VibeReaderDatabase.getInstance(context) }
        viewModel(
            factory = remember(context, database) {
                SettingsViewModel.Factory(database, context.applicationContext)
            },
        )
    },
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
    // Safe collection with non-null initial values; never read uninitialized flows.
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle(initialValue = ThemeOption.SYSTEM)
    val themeError by viewModel.themeError.collectAsStateWithLifecycle(initialValue = null)
    val parserRepository = remember(context, database) {
        BookParserRepository(context.applicationContext, database)
    }
    val wallpaperPreferences = remember(context) {
        runCatching { WallpaperPreferences(context.applicationContext) }.getOrNull()
    }
    val coroutineScope = rememberCoroutineScope()
    var wallpaperSettings by remember(wallpaperPreferences) {
        mutableStateOf(
            runCatching { wallpaperPreferences?.getSettings() }.getOrNull() ?: WallpaperSettings(),
        )
    }
    var wallpaperTargetMenuExpanded by remember { mutableStateOf(false) }
    val dictionaryPreferences = remember(context) {
        runCatching { DictionaryPreferences(context.applicationContext) }.getOrNull()
    }
    var customDictionaryPackage by remember(dictionaryPreferences) {
        mutableStateOf(
            runCatching { dictionaryPreferences?.getCustomDictionaryPackage() }.getOrNull(),
        )
    }
    var dictionaryMenuExpanded by remember { mutableStateOf(false) }
    val dictionaryApps by produceState<List<AppInfo>?>(initialValue = null) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                AppDiscoveryHelper.getAvailableDictionaryApps(context)
            }
        }.getOrDefault(emptyList())
    }
    val installedDictionaryApps = (dictionaryApps ?: emptyList()).filterNotNull()
    val selectedDictionaryLabel = when {
        customDictionaryPackage.isNullOrBlank() -> "Google Search (default)"
        dictionaryApps == null -> "Loading…"
        else -> installedDictionaryApps
            .firstOrNull { it.packageName == customDictionaryPackage }
            ?.appName
            ?: "Selected app unavailable"
    }
    var message by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var showImportOptions by remember { mutableStateOf(false) }

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
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                libraryViewModel.saveFolderAccess(uri)
                message = "Folder access saved. Scanning the selected folder."
            }.onFailure { error ->
                message = error.localizedMessage ?: "Folder access could not be saved."
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "SETTINGS",
            style = MaterialTheme.typography.headlineSmall,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )

        SettingsCard("STORAGE & BOOKS") {
            Text(
                text = "Import PDF or EPUB files, or rescan your device library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsPrimaryButton(
                text = if (isImporting) "Importing books…" else "Import Books (PDF / EPUB)",
                onClick = { showImportOptions = true },
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = libraryViewModel::scanDeviceForBooks,
                    enabled = !scanState.isScanning && !scanState.isProcessingMetadata,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = if (scanState.isScanning) "Scanning…" else "Rescan Library",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            AnimatedVisibility(
                visible = scanState.isScanning ||
                    scanState.isProcessingMetadata ||
                    scanState.statusMessage != null ||
                    scanState.errorMessage != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (scanState.isScanning || scanState.isProcessingMetadata) {
                        Text(
                            text = scanState.statusMessage ?: "Updating your library…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        scanState.statusMessage?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    scanState.errorMessage?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = message != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        SettingsCard("APPEARANCE & THEME") {
            Text(
                text = "Choose the app appearance. Reading canvas themes are set in Reading Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeOption.entries.toList().forEach { option ->
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
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
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
                    val pillBorder by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        },
                        label = "theme-pill-border",
                    )
                    Surface(
                        onClick = { runCatching { viewModel.setTheme(option) } },
                        shape = RoundedCornerShape(50),
                        color = pillBackground,
                        contentColor = pillForeground,
                        border = BorderStroke(1.dp, pillBorder),
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelMedium,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = themeError != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                themeError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        SettingsCard("DICTIONARY") {
            Text(
                text = "Select a single word in the reader, then tap 📖 Dictionary. " +
                    "Choose an installed dictionary or translation app, or leave this on " +
                    "Google Search to look up the definition.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                SettingsPrimaryButton(
                    text = "Choose dictionary app",
                    onClick = { dictionaryMenuExpanded = true },
                )
                DropdownMenu(
                    expanded = dictionaryMenuExpanded,
                    onDismissRequest = { dictionaryMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Google Search (default)") },
                        onClick = {
                            customDictionaryPackage = null
                            runCatching { dictionaryPreferences?.setCustomDictionaryPackage(null) }
                            dictionaryMenuExpanded = false
                        },
                    )
                    installedDictionaryApps.forEach { app ->
                        DropdownMenuItem(
                            text = { Text(app.appName) },
                            leadingIcon = {
                                DictionaryAppIcon(
                                    app = app,
                                    modifier = Modifier.size(28.dp),
                                )
                            },
                            onClick = {
                                customDictionaryPackage = app.packageName
                                runCatching {
                                    dictionaryPreferences?.setCustomDictionaryPackage(app.packageName)
                                }
                                dictionaryMenuExpanded = false
                            },
                        )
                    }
                    when {
                        dictionaryApps == null -> DropdownMenuItem(
                            text = { Text("Loading installed apps…") },
                            enabled = false,
                            onClick = {},
                        )
                        installedDictionaryApps.isEmpty() -> DropdownMenuItem(
                            text = { Text("No dictionary/translation apps found") },
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }
            Text(
                text = "Current: $selectedDictionaryLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AnimatedVisibility(
                visible = !customDictionaryPackage.isNullOrBlank() &&
                    dictionaryApps != null &&
                    installedDictionaryApps.none { it.packageName == customDictionaryPackage },
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Text(
                    text = "The selected app is no longer installed. The Google fallback will be used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SettingsCard("WALLPAPER AI") {
            Text(
                text = "Choose where generated reading wallpapers are applied. Quotes are " +
                    "curated by the connected AI providers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                SettingsPrimaryButton(
                    text = "Apply wallpaper to: ${wallpaperSettings.applyTarget.label}",
                    onClick = { wallpaperTargetMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = wallpaperTargetMenuExpanded,
                    onDismissRequest = { wallpaperTargetMenuExpanded = false },
                ) {
                    WallpaperTarget.entries.toList().forEach { target ->
                        DropdownMenuItem(
                            text = { Text(target.label) },
                            onClick = {
                                val prefs = wallpaperPreferences
                                // Merge into the latest persisted settings so a
                                // stale snapshot cannot revert the Studio theme.
                                val latest = runCatching {
                                    prefs?.getSettings()?.copy(applyTarget = target)
                                }.getOrNull() ?: wallpaperSettings.copy(applyTarget = target)
                                wallpaperSettings = latest
                                runCatching { prefs?.saveSettings(latest) }
                                wallpaperTargetMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        ApiKeySettingsCard(settingsViewModel = viewModel)
    }

    if (showImportOptions) {
        AlertDialog(
            onDismissRequest = { showImportOptions = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = "Import books",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = "Choose books to import, or select a folder to scan.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportOptions = false
                        runCatching {
                            documentPicker.launch(
                                arrayOf("application/pdf", "application/epub+zip"),
                            )
                        }
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
                            runCatching { folderPicker.launch(null) }
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
private fun SettingsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DictionaryAppIcon(
    app: AppInfo,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(app.packageName) {
        runCatching {
            val size = 48
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            app.icon.setBounds(0, 0, size, size)
            app.icon.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
    imageBitmap?.let { bitmap ->
        Icon(
            painter = BitmapPainter(bitmap),
            contentDescription = app.appName,
            modifier = modifier,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
        )
    }
}

@Composable
internal fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        ),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

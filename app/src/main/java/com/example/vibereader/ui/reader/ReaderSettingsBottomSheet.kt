package com.example.vibereader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.vibereader.model.ReaderTtsVoice
import com.example.vibereader.model.ReadingTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsBottomSheet(
    settings: ReaderSettings,
    isPdf: Boolean,
    ttsVoice: ReaderTtsVoice,
    onTtsVoiceChange: (ReaderTtsVoice) -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    onScrollModeChange: (ScrollMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Reading settings", style = MaterialTheme.typography.headlineSmall)
            Text("Page navigation", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScrollMode.entries.forEach { mode ->
                    val onClick = {
                        onScrollModeChange(mode)
                    }
                    if (settings.scrollMode == mode) {
                        Button(onClick = onClick, modifier = Modifier.weight(1f)) {
                            Text(mode.label)
                        }
                    } else {
                        OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
                            Text(mode.label)
                        }
                    }
                }
            }
            Text("Reading canvas theme", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReadingTheme.entries.forEach { theme ->
                    ReadingThemeOption(
                        theme = theme,
                        selected = settings.readingTheme == theme,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSettingsChange(settings.copy(readingTheme = theme))
                        },
                    )
                }
            }

            Text("Text-to-speech voice", style = MaterialTheme.typography.titleMedium)
            ReaderTtsVoice.entries.forEach { voice ->
                if (ttsVoice == voice) {
                    Button(onClick = { onTtsVoiceChange(voice) }, modifier = Modifier.fillMaxWidth()) {
                        Text(voice.label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onTtsVoiceChange(voice) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(voice.label)
                    }
                }
            }

            if (!isPdf) {
                Spacer(modifier = Modifier.height(4.dp))
                SettingSlider(
                    label = "Font size",
                    valueLabel = "${settings.fontSizeSp.toInt()} sp",
                    value = settings.fontSizeSp,
                    valueRange = 12f..28f,
                    onValueChange = { onSettingsChange(settings.copy(fontSizeSp = it)) },
                )
                SettingSlider(
                    label = "Line height",
                    valueLabel = "${(settings.lineHeightMultiplier * 100).toInt()}%",
                    value = settings.lineHeightMultiplier,
                    valueRange = 1.2f..2.2f,
                    onValueChange = { onSettingsChange(settings.copy(lineHeightMultiplier = it)) },
                )
            }
            SettingSlider(
                label = "Page margins",
                valueLabel = "${settings.marginDp.toInt()} dp",
                value = settings.marginDp,
                valueRange = 0f..40f,
                onValueChange = { onSettingsChange(settings.copy(marginDp = it)) },
            )
        }
    }
}

@Composable
private fun ReadingThemeOption(
    theme: ReadingTheme,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val systemDarkTheme = isSystemInDarkTheme()
    val (label, backgroundColor) = when (theme) {
        ReadingTheme.SYSTEM -> "System" to
            if (systemDarkTheme) Color(0xFF121212) else Color.White
        ReadingTheme.OLED_BLACK -> "OLED" to Color.Black
        ReadingTheme.SEPIA -> "Sepia" to Color(0xFFF8F1E5)
        ReadingTheme.LIGHT -> "Light" to Color.White
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Transparent,
        ) {
            Spacer(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(backgroundColor)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

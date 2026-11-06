package com.example.vibereader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vibereader.util.FreeAiTtsPlayer

@Composable
fun AudioPlayerOverlay(
    player: FreeAiTtsPlayer,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loading by player.isLoading
    val playing by player.isPlaying
    val nativeFallback by player.isUsingNativeFallback
    val error by player.error
    if (!loading && !playing && !nativeFallback && error == null) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                loading -> {
                    CircularProgressIndicator()
                    Text(
                        "Preparing Edge neural audio…",
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                nativeFallback -> Text(
                    "Edge TTS unavailable; using Android system voice.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                error != null -> Text(
                    error.orEmpty(),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text(
                    "Playing Edge neural voice",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (playing) {
                TextButton(onClick = player::togglePlayback) { Text("Pause") }
            }
            TextButton(onClick = onStop) { Text("Stop") }
        }
    }
}

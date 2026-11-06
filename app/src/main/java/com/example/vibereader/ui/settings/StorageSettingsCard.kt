package com.example.vibereader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StorageSettingsCard(
    isImporting: Boolean,
    isScanning: Boolean,
    isProcessingMetadata: Boolean,
    statusMessage: String?,
    errorMessage: String?,
    onImportBooksClicked: () -> Unit,
    onRescanClicked: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "STORAGE & BOOKS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onImportBooksClicked,
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isImporting) "Importing books…" else "Import Books (PDF / EPUB)")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onRescanClicked,
                    enabled = !isScanning && !isProcessingMetadata,
                ) {
                    Text(
                        if (isScanning) "Scanning…" else "Rescan Library",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (isScanning || isProcessingMetadata) {
                Text(
                    statusMessage ?: "Updating your library…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                statusMessage?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
            errorMessage?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

package com.example.vibereader.ui.quotes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.local.WallpaperUpdateTrigger
import com.example.vibereader.service.WallpaperUpdateScheduler
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.util.FileSharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

typealias WallpaperUpdateFrequency = WallpaperUpdateTrigger

@Composable
fun QuotesScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember(context) { VibeReaderDatabase.getInstance(context) }
    val quotes by database.quoteDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val highlights by database.highlightDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val books by database.bookDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val titlesByBook = remember(books) { books.associate { it.id to it.title } }
    val hasWallpaperQuote = quotes.any { it.text.isNotBlank() } ||
        highlights.any { it.text.isNotBlank() }
    val wallpaperPreferences = remember(context) { WallpaperPreferences(context) }
    var wallpaperSettings by remember(wallpaperPreferences) {
        mutableStateOf(wallpaperPreferences.getSettings())
    }
    LaunchedEffect(wallpaperSettings.isWallpaperEnabled, wallpaperSettings.updateTrigger) {
        WallpaperUpdateScheduler.configure(
            context,
            wallpaperSettings.updateTrigger,
            wallpaperSettings.isWallpaperEnabled,
        )
    }
    val workInfos by remember(context) {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(WallpaperUpdateScheduler.MANUAL_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val wallpaperStatus = workInfos.firstOrNull()?.let { workInfo ->
        when (workInfo.state) {
            androidx.work.WorkInfo.State.ENQUEUED,
            androidx.work.WorkInfo.State.BLOCKED,
            -> "Waiting to render wallpaper…"
            androidx.work.WorkInfo.State.RUNNING -> "Rendering and applying wallpaper offline…"
            androidx.work.WorkInfo.State.SUCCEEDED -> "Wallpaper generated and applied."
            androidx.work.WorkInfo.State.FAILED -> workInfo.outputData.getString("error")
                ?: "Wallpaper generation failed."
            androidx.work.WorkInfo.State.CANCELLED -> "Wallpaper generation cancelled."
        }
    }
    LaunchedEffect(workInfos.firstOrNull()?.state) {
        if (workInfos.firstOrNull()?.state == androidx.work.WorkInfo.State.SUCCEEDED) {
            wallpaperSettings = wallpaperPreferences.getSettings()
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            WallpaperStudioSection(
                currentWallpaperUri = wallpaperSettings.currentWallpaperPath,
                selectedFrequency = if (wallpaperSettings.isWallpaperEnabled) {
                    wallpaperSettings.updateTrigger
                } else {
                    WallpaperUpdateTrigger.OFF
                },
                onFrequencySelected = { frequency ->
                    wallpaperSettings = wallpaperSettings.copy(
                        updateTrigger = frequency,
                        isWallpaperEnabled = frequency != WallpaperUpdateTrigger.OFF,
                    )
                    wallpaperPreferences.saveSettings(wallpaperSettings)
                    WallpaperUpdateScheduler.configure(
                        context,
                        frequency,
                        wallpaperSettings.isWallpaperEnabled,
                    )
                },
                onGenerateClicked = {
                    if (hasWallpaperQuote) WallpaperUpdateScheduler.enqueueNow(context)
                },
                statusMessage = wallpaperStatus,
                hasQuoteOrHighlight = hasWallpaperQuote,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SAVED QUOTES", style = MaterialTheme.typography.headlineSmall)
                if (quotes.isEmpty()) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Text(
                            "Quotes you save while reading will appear here.",
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        items(quotes, key = QuoteEntity::id) { quote ->
            QuoteCard(
                quote = quote,
                bookTitle = titlesByBook[quote.bookId] ?: "Unknown book",
                onShareText = { FileSharer.shareQuoteText(context, quote, titlesByBook[quote.bookId] ?: "Unknown book") },
                onShareImage = {
                    FileSharer.shareQuoteImage(
                        context,
                        quote,
                        titlesByBook[quote.bookId] ?: "Unknown book",
                    )
                },
                onDeleteQuote = {
                    coroutineScope.launch {
                        try {
                            database.quoteDao().delete(quote)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Toast.makeText(
                                context,
                                error.localizedMessage ?: "Could not delete this quote.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            )
        }
    }
}

@Composable
fun WallpaperStudioSection(
    currentWallpaperUri: String?,
    selectedFrequency: WallpaperUpdateFrequency,
    onFrequencySelected: (WallpaperUpdateFrequency) -> Unit,
    onGenerateClicked: () -> Unit,
    statusMessage: String? = null,
    hasQuoteOrHighlight: Boolean = true,
) {
    val previewBitmap by produceState<Bitmap?>(
        initialValue = null,
        currentWallpaperUri,
        statusMessage,
    ) {
        value = currentWallpaperUri?.let { path ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "WALLPAPER STUDIO",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.Start),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .aspectRatio(9f / 16f)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap!!.asImageBitmap(),
                        contentDescription = "Active generated wallpaper",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = "No wallpaper generated yet",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "AUTOMATIC UPDATE FREQUENCY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            ) {
                WallpaperUpdateTrigger.entries.forEachIndexed { index, frequency ->
                    SegmentedButton(
                        selected = selectedFrequency == frequency,
                        onClick = { onFrequencySelected(frequency) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = WallpaperUpdateTrigger.entries.size,
                        ),
                    ) {
                        Text(
                            text = frequency.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onGenerateClicked,
                enabled = hasQuoteOrHighlight,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Generate & Apply New Wallpaper")
            }
            if (!hasQuoteOrHighlight) {
                Text(
                    "Save a quote or highlight while reading to create an offline typography wallpaper.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            statusMessage?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.contains("failed", ignoreCase = true)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun QuoteCard(
    quote: QuoteEntity,
    bookTitle: String,
    onShareText: () -> Unit,
    onShareImage: () -> Unit,
    onDeleteQuote: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "“${quote.text}”",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "— $bookTitle",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.TextButton(
                onClick = onDeleteQuote,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Delete")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShareText) {
                    Text("SHARE TEXT")
                }
                Button(onClick = onShareImage) {
                    Text("SHARE IMAGE")
                }
            }
        }
    }
}

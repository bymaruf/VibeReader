package com.example.vibereader.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as columnItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.ReadingProgressEntity
import com.example.vibereader.util.FileSharer
import com.example.vibereader.util.displayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues = PaddingValues(),
    scanEnabled: Boolean = true,
    onBookSelected: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val database = remember(context) { VibeReaderDatabase.getInstance(context) }
    val libraryViewModel: LibraryViewModel = viewModel(
        factory = remember(context, database) {
            LibraryViewModel.Factory(context.applicationContext, database)
        },
    )
    val books by libraryViewModel.books.collectAsStateWithLifecycle(initialValue = emptyList())
    val scanState by libraryViewModel.scanState.collectAsStateWithLifecycle()
    val progress by database.readingProgressDao().observeAll().collectAsState(initial = emptyList())
    val progressByBook = remember(progress) { progress.associateBy { it.bookId } }
    val scannedBooks = remember(books) { books.filter { it.isDeviceScanned } }
    val haptic = LocalHapticFeedback.current
    var gridLayout by remember { mutableStateOf(true) }

    LaunchedEffect(scanEnabled) {
        if (scanEnabled) libraryViewModel.scanDeviceForBooks()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "LIBRARY",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Your books, ready when you are.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        scanState.errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Crossfade(
            targetState = scanState.isScanning || scanState.isProcessingMetadata,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            label = "library-scan-state",
        ) { scanning ->
            if (scanning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        scanState.statusMessage ?: "Scanning for books…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (scannedBooks.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "RECENTLY READ",
                    style = MaterialTheme.typography.titleSmall,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(true to "GRID", false to "LIST").forEach { (isGrid, label) ->
                        val selected = gridLayout == isGrid
                        val containerColor by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.surfaceVariant,
                            label = "library-layout-toggle",
                        )
                        val contentColor by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.surface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            label = "library-layout-toggle-text",
                        )
                        Surface(
                            onClick = { gridLayout = isGrid },
                            shape = RoundedCornerShape(50),
                            color = containerColor,
                            contentColor = contentColor,
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        AnimatedContent(
            targetState = when {
                scanState.isScanning || scanState.isProcessingMetadata -> "loading"
                scannedBooks.isEmpty() -> "empty"
                else -> "books"
            },
            modifier = Modifier.weight(1f),
            label = "library-content",
        ) { content ->
            when (content) {
                "loading" -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (scannedBooks.isEmpty()) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    } else {
                        BookCollection(scannedBooks, progressByBook, onBookSelected, haptic, gridLayout, context)
                    }
                }
                "empty" -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "NO BOOKS YET",
                        style = MaterialTheme.typography.titleMedium,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "No books discovered yet. Use Settings to scan a folder or add a book.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> AnimatedContent(
                    targetState = gridLayout,
                    label = "library-grid-list",
                ) { showGrid ->
                    BookCollection(
                        scannedBooks,
                        progressByBook,
                        onBookSelected,
                        haptic,
                        showGrid,
                        context,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCollection(
    books: List<BookEntity>,
    progressByBook: Map<String, ReadingProgressEntity>,
    onBookSelected: (String) -> Unit,
    haptic: HapticFeedback,
    isGrid: Boolean,
    context: android.content.Context,
) {
    if (isGrid) BookGrid(books, progressByBook, onBookSelected, haptic, context)
    else BookList(books, progressByBook, onBookSelected, haptic, context)
}

@Composable
private fun BookGrid(
    books: List<BookEntity>,
    progressByBook: Map<String, ReadingProgressEntity>,
    onBookSelected: (String) -> Unit,
    haptic: HapticFeedback,
    context: android.content.Context,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItems(books, key = { it.id }) { book ->
            val readingProgress = progressByBook[book.id]?.progress?.coerceIn(0f, 1f) ?: 0f
            val displayTitle = book.displayTitle()
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.975f else 1f,
                animationSpec = spring(stiffness = 500f, dampingRatio = 0.72f),
                label = "book-card-scale",
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onBookSelected(book.id)
                        },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    ),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        BookCover(
                            title = displayTitle,
                            coverImagePath = book.coverImagePath,
                            modifier = Modifier.fillMaxSize(),
                        )
                        FormatBadge(
                            format = book.format,
                            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                        )
                        BookShareMenu(
                            book = book,
                            context = context,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            minLines = 2,
                        )
                        Text(
                            text = book.author ?: "Unknown author",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${(readingProgress * 100).toInt()}% read",
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ReadingProgressBar(readingProgress)
                }
            }
        }
    }
}

@Composable
private fun BookList(
    books: List<BookEntity>,
    progressByBook: Map<String, ReadingProgressEntity>,
    onBookSelected: (String) -> Unit,
    haptic: HapticFeedback,
    context: android.content.Context,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        columnItems(books, key = { it.id }) { book ->
            val progress = progressByBook[book.id]?.progress?.coerceIn(0f, 1f) ?: 0f
            val displayTitle = book.displayTitle()
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.985f else 1f,
                animationSpec = spring(stiffness = 500f, dampingRatio = 0.72f),
                label = "book-list-item-scale",
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onBookSelected(book.id)
                        },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    ),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(78.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        BookCover(
                            title = displayTitle,
                            coverImagePath = book.coverImagePath,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            book.author ?: "Unknown author",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        ReadingProgressBar(progress, Modifier.padding(top = 7.dp))
                    }
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BookShareMenu(book = book, context = context)
                }
            }
        }
    }
}

@Composable
private fun BookShareMenu(
    book: BookEntity,
    context: android.content.Context,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(book.id) { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics {
                contentDescription = "Actions for ${book.displayTitle()}"
            },
        ) {
            Text(
                text = "⋮",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Share file") },
                onClick = {
                    expanded = false
                    FileSharer.shareBookFile(context, book)
                },
            )
        }
    }
}

@Composable
private fun BookCover(
    title: String,
    coverImagePath: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, coverImagePath) {
        value = withContext(Dispatchers.IO) {
            coverImagePath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val cover = bitmap
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = "$title cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = title.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FormatBadge(format: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = "[${format.uppercase()}]",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ReadingProgressBar(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp),
        color = MaterialTheme.colorScheme.onSurface,
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    )
}

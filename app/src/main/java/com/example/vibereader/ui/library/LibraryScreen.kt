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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
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
    val searchQuery by libraryViewModel.searchQuery.collectAsStateWithLifecycle()
    val scanState by libraryViewModel.scanState.collectAsStateWithLifecycle()
    val progress by database.readingProgressDao().observeAll().collectAsState(initial = emptyList())
    val progressByBook = remember(progress) { progress.associateBy { it.bookId } }
    val scannedBooks = remember(books) { books.filter { it.isDeviceScanned } }
    val haptic = LocalHapticFeedback.current
    var gridLayout by remember { mutableStateOf(true) }
    var isSearchActive by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(scanEnabled) {
        if (scanEnabled) libraryViewModel.scanDeviceForBooks()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            // Scaffold/NavHost already apply the status-bar inset; only add the
            // compact 4.dp breathing room on top of it.
            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Crossfade(
            targetState = isSearchActive,
            modifier = Modifier.fillMaxWidth(),
            label = "library-search-header",
        ) { active ->
            if (active) {
                LaunchedEffect(Unit) {
                    searchFocusRequester.requestFocus()
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = libraryViewModel::setSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester),
                    placeholder = { Text("Search title or author...") },
                    leadingIcon = {
                        IconButton(
                            onClick = {
                                libraryViewModel.setSearchQuery("")
                                isSearchActive = false
                            },
                        ) {
                            Icon(
                                imageVector = LibraryIcons.ArrowBack,
                                contentDescription = "Close search",
                            )
                        }
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { libraryViewModel.setSearchQuery("") }) {
                                Icon(
                                    imageVector = LibraryIcons.Clear,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                )
            } else {
                LibraryHeaderRow(
                    gridLayout = gridLayout,
                    onGridLayoutChange = { gridLayout = it },
                    onSearchClick = { isSearchActive = true },
                )
            }
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
            Text(
                text = "RECENTLY READ",
                modifier = Modifier.padding(vertical = 2.dp),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedContent(
            targetState = when {
                scanState.isScanning || scanState.isProcessingMetadata -> "loading"
                scannedBooks.isEmpty() && searchQuery.isNotBlank() -> "no_results"
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
                "no_results" -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "NO MATCHES",
                        style = MaterialTheme.typography.titleMedium,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "No books match \"${searchQuery.trim()}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
private fun LibraryHeaderRow(
    gridLayout: Boolean,
    onGridLayoutChange: (Boolean) -> Unit,
    onSearchClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LIBRARY",
            fontSize = 20.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onSearchClick)
                .semantics { contentDescription = "Search" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LibraryIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        LibraryLayoutToggle(
            gridLayout = gridLayout,
            onGridLayoutChange = onGridLayoutChange,
        )
    }
}

@Composable
private fun LibraryLayoutToggle(
    gridLayout: Boolean,
    onGridLayoutChange: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(containerColor)
                    .clickable { onGridLayoutChange(isGrid) }
                    .padding(horizontal = 9.dp)
                    .semantics { contentDescription = "$label view" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                )
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rowSpacing = 8.dp
        val safeHeight = if (maxHeight.value.isFinite() && maxHeight > 0.dp) {
            maxHeight
        } else {
            640.dp
        }
        // Exactly three rows fit the viewport: 2 columns x 3 rows = 6 covers visible.
        val cardHeight = ((safeHeight - rowSpacing * 2) / 3f).coerceAtLeast(140.dp)

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
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
                        .height(cardHeight)
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
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        ),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            BookCover(
                                title = displayTitle,
                                coverImagePath = book.coverImagePath,
                                modifier = Modifier.fillMaxSize(),
                            )
                            FormatBadge(
                                format = book.format,
                                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                            )
                            BookShareMenu(
                                book = book,
                                context = context,
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                        Column(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = displayTitle,
                                fontSize = 12.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                minLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = book.author ?: "Unknown author",
                                    modifier = Modifier.weight(1f),
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${(readingProgress * 100).toInt()}%",
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        ReadingProgressBar(readingProgress)
                    }
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
            modifier = Modifier
                .size(32.dp)
                .semantics {
                    contentDescription = "Actions for ${book.displayTitle()}"
                },
        ) {
            Text(
                text = "⋮",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
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

/**
 * Minimal local icon set for the library search field. The Material Icons
 * artifact is not part of this project's dependencies, so the required glyphs
 * are defined here as plain [ImageVector]s.
 */
private object LibraryIcons {
    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "LibrarySearch",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(
                "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 " +
                    "16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16" +
                    "c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99" +
                    "L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5" +
                    "S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
    }

    val ArrowBack: ImageVector by lazy {
        ImageVector.Builder(
            name = "LibraryArrowBack",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).addPath(
            pathData = PathParser().parsePathString(
                "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
    }

    val Clear: ImageVector by lazy {
        ImageVector.Builder(
            name = "LibraryClear",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(
                "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 " +
                    "5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
    }
}

package com.example.vibereader.ui.quotes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkManager
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.local.WallpaperUpdateTrigger
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.data.room.QuoteSource
import com.example.vibereader.domain.model.TypographyWallpaper
import com.example.vibereader.service.WallpaperRotationManager
import com.example.vibereader.util.FileSharer
import com.example.vibereader.util.FilenameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

typealias WallpaperUpdateFrequency = WallpaperUpdateTrigger

private const val TAG = "WallpaperRotation"

private val FlatCardShape = RoundedCornerShape(18.dp)
private val FlatControlShape = RoundedCornerShape(12.dp)
private val FlatPillShape = RoundedCornerShape(50)

/**
 * Every top-level section and quote uses exactly one flat surface. There are no
 * outer wrapping cards around inner cards, so a theme change paints a single
 * clean background per item.
 */
@Composable
private fun flatCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface,
)

@Composable
private fun flatCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = MaterialTheme.colorScheme.onSurface,
    checkmarkColor = MaterialTheme.colorScheme.surface,
    uncheckedColor = MaterialTheme.colorScheme.outline,
)

@Composable
fun QuotesScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val database = remember(context) { VibeReaderDatabase.getInstance(context) }
    val quotesViewModel: QuotesViewModel = viewModel(
        factory = QuotesViewModel.Factory(context, database),
    )
    val wallpaperViewModel: WallpaperStudioViewModel = viewModel(
        factory = WallpaperStudioViewModel.Factory(context, database),
    )
    // Full unfiltered DB snapshot for accurate global counters.
    val allQuotes by quotesViewModel.quotes.collectAsStateWithLifecycle()
    val books by quotesViewModel.books.collectAsStateWithLifecycle()
    val quoteGroups by quotesViewModel.groupedQuotes.collectAsStateWithLifecycle()
    val quoteFilter by quotesViewModel.filter.collectAsStateWithLifecycle()
    val sortNewestFirst by quotesViewModel.sortNewestFirst.collectAsStateWithLifecycle()
    val selectedQuoteBookIds by quotesViewModel.selectedBookIds.collectAsStateWithLifecycle()
    val selectedQuoteIds by quotesViewModel.selectedQuoteIds.collectAsStateWithLifecycle()
    val isFetchingWallpaperQuotes by quotesViewModel.isFetchingQuotes.collectAsStateWithLifecycle()
    val fetchedQuoteCount by quotesViewModel.newQuotesFetchedCount.collectAsStateWithLifecycle()
    val quoteMessage by quotesViewModel.message.collectAsStateWithLifecycle()
    val titlesByBook = remember(books) {
        books.associate { book ->
            book.id to FilenameSanitizer.cleanBookTitle(book.title).ifBlank { "Untitled Book" }
        }
    }
    val bookFilterOptions = remember(books, titlesByBook) {
        books
            .map { book -> book.id to (titlesByBook[book.id] ?: "Untitled Book") }
            .sortedBy { (_, title) -> title.lowercase() }
    }
    val wallpapers by wallpaperViewModel.wallpapers.collectAsStateWithLifecycle()
    val isGeneratingWallpaper by wallpaperViewModel.isGenerating.collectAsStateWithLifecycle()
    val isLoadingMoreWallpapers by wallpaperViewModel.isLoadingMore.collectAsStateWithLifecycle()
    val wallpaperError by wallpaperViewModel.error.collectAsStateWithLifecycle()
    val selectedBookIds by wallpaperViewModel.selectedBookIds.collectAsStateWithLifecycle()
    val isDarkWallpaperTheme by wallpaperViewModel.isDarkMode.collectAsStateWithLifecycle()

    // Approval and deletion events come from QuotesViewModel, so the
    // carousel can append/remove its preview immediately.
    LaunchedEffect(quotesViewModel) {
        quotesViewModel.quoteEvents.collect {
            wallpaperViewModel.syncWallpapersWithApprovals()
        }
    }
    // Human-in-the-loop: only explicitly approved quotes may become wallpapers.
    val canGenerateWallpaper = allQuotes.any { it.isApprovedForWallpaper }
    val wallpaperPreferences = remember(context) { WallpaperPreferences(context) }
    var wallpaperSettings by remember(wallpaperPreferences) {
        mutableStateOf(wallpaperPreferences.getSettings())
    }
    val effectiveTrigger = if (wallpaperSettings.isWallpaperEnabled) {
        wallpaperSettings.updateTrigger
    } else {
        WallpaperUpdateTrigger.OFF
    }
    LaunchedEffect(effectiveTrigger) {
        Log.d(TAG, "Applying effective wallpaper trigger from UI: $effectiveTrigger")
        WallpaperRotationManager.updateFrequency(context, effectiveTrigger)
    }
    val workInfos by remember(context) {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(WallpaperRotationManager.MANUAL_WORK_NAME)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
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
    val approvedCount = allQuotes.count { it.isApprovedForWallpaper }
    val userCount = allQuotes.count { it.source == QuoteSource.USER }
    val aiCount = allQuotes.count { it.source == QuoteSource.AI }

    // Everything currently rendered by the quote library. "Select All" and the
    // bulk actions are intentionally scoped to this visible set.
    val visibleQuotes = remember(quoteGroups) { quoteGroups.flatMap { it.quotes } }
    val visibleQuoteIds = remember(visibleQuotes) { visibleQuotes.map { it.id }.toSet() }
    val selectedVisibleQuoteIds = remember(selectedQuoteIds, visibleQuoteIds) {
        selectedQuoteIds.intersect(visibleQuoteIds)
    }
    val allVisibleSelected = visibleQuoteIds.isNotEmpty() &&
        selectedVisibleQuoteIds.size == visibleQuoteIds.size

    // Approved quotes matching the quote-library book filter context. Used to
    // keep carousel preview tracking aligned with approved quote state.
    val approvedQuotesForPreview = remember(allQuotes, selectedQuoteBookIds) {
        allQuotes.filter { quote ->
            quote.isApprovedForWallpaper &&
                (selectedQuoteBookIds.isEmpty() || quote.bookId in selectedQuoteBookIds)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "wallpaper-studio", contentType = "wallpaper-studio") {
            WallpaperStudioSection(
                wallpapers = wallpapers,
                approvedQuotes = approvedQuotesForPreview,
                books = books,
                selectedBookIds = selectedBookIds,
                isDarkTheme = isDarkWallpaperTheme,
                currentWallpaperUri = wallpaperSettings.currentWallpaperPath,
                selectedFrequency = effectiveTrigger,
                onFrequencySelected = { frequency ->
                    // Merge into the freshest persisted settings instead of
                    // this composable's snapshot. A stale copy would write the
                    // previous theme back over the Studio toggle.
                    // Tapping "Manual" (OFF) sets dynamic update frequency to
                    // manual and stops auto-rotation via WallpaperRotationManager.
                    val latest = wallpaperPreferences.getSettings()
                    val updated = latest.copy(
                        updateTrigger = frequency,
                        isWallpaperEnabled = frequency != WallpaperUpdateTrigger.OFF,
                    )
                    wallpaperSettings = updated
                    wallpaperPreferences.saveSettings(updated)
                    Log.d(TAG, "Frequency selected in UI: $frequency (enabled=${updated.isWallpaperEnabled})")
                    WallpaperRotationManager.updateFrequency(context, frequency)
                },
                onGenerateClicked = {
                    if (canGenerateWallpaper) {
                        Log.d(TAG, "Generate/apply requested from UI without a preview bitmap")
                        WallpaperRotationManager.enqueueNow(context)
                    }
                },
                onSetWallpaper = { wallpaper ->
                    wallpaperViewModel.setActiveWallpaper(wallpaper)
                },
                onBookSelectionChanged = { ids ->
                    wallpaperViewModel.selectBooks(ids)
                },
                onThemeChanged = { isDark ->
                    // Clicking Light/Dark instantly persists and re-renders the
                    // visible wallpaper preview bitmap via WallpaperStudioViewModel.
                    wallpaperViewModel.setWallpaperTheme(isDark)
                },
                onLoadMore = {
                    wallpaperViewModel.fetchNextWallpaperBatch()
                },
                onFetchQuotes = {
                    quotesViewModel.startFetchingQuotes(selectedBookIds)
                },
                onStopFetchingQuotes = quotesViewModel::stopFetchingQuotes,
                onActivePreviewChanged = { wallpaper ->
                    val matchedQuote = wallpaper?.let { preview ->
                        allQuotes.firstOrNull { quote ->
                            quote.isApprovedForWallpaper &&
                                quote.selectedText == preview.quote
                        }
                    }
                    quotesViewModel.selectQuoteForPreview(matchedQuote)
                },
                onManualApplyRequested = { wallpaper ->
                    // Manual mode: render the exact quote+theme currently shown in
                    // the preview canvas, set it via WallpaperManager.setBitmap(),
                    // and cancel auto-rotation WorkManager jobs so it stays fixed.
                    quotesViewModel.applyManualWallpaper(
                        wallpaper = wallpaper,
                        isDarkMode = isDarkWallpaperTheme,
                    )
                    val latest = wallpaperPreferences.getSettings().copy(
                        updateTrigger = WallpaperUpdateTrigger.OFF,
                        isWallpaperEnabled = false,
                        isDarkTheme = isDarkWallpaperTheme,
                    )
                    wallpaperSettings = latest
                },
                fetchedQuoteCount = fetchedQuoteCount,
                statusMessage = quoteMessage ?: wallpaperError ?: wallpaperStatus,
                canGenerate = canGenerateWallpaper,
                isGenerating = isGeneratingWallpaper,
                isLoadingMore = isLoadingMoreWallpapers,
                isFetchingQuotes = isFetchingWallpaperQuotes,
            )
        }
        item(key = "quote-library-header", contentType = "quote-library-header") {
            Column(
                modifier = Modifier.animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "QUOTE LIBRARY",
                            style = MaterialTheme.typography.headlineSmall,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "$approvedCount approved • $userCount yours • $aiCount AI",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = quotesViewModel::toggleSortOrder) {
                        Text(
                            text = if (sortNewestFirst) "Newest ↓" else "Oldest ↑",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuoteFilter.entries.forEach { filter ->
                        ModernFilterChip(
                            label = filter.label,
                            selected = quoteFilter == filter,
                            onClick = { quotesViewModel.setFilter(filter) },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = bookFilterOptions.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "FILTER BY BOOK",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ModernFilterChip(
                                label = "All Books",
                                selected = selectedQuoteBookIds.isEmpty(),
                                onClick = quotesViewModel::clearBookFilters,
                            )
                            bookFilterOptions.forEach { (bookId, title) ->
                                ModernFilterChip(
                                    label = title,
                                    selected = bookId in selectedQuoteBookIds,
                                    onClick = { quotesViewModel.toggleBookFilter(bookId) },
                                    modifier = Modifier.widthIn(max = 200.dp),
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "Your saved quotes always stay above AI suggestions. " +
                        "Newly fetched quotes are appended and every existing approval is kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedVisibility(
                    visible = visibleQuotes.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    QuoteBatchActions(
                        visibleCount = visibleQuoteIds.size,
                        selectedCount = selectedVisibleQuoteIds.size,
                        allSelected = allVisibleSelected,
                        onSelectAllChanged = { checked ->
                            if (checked) {
                                quotesViewModel.selectAllVisible(visibleQuoteIds)
                            } else {
                                quotesViewModel.clearSelection()
                            }
                        },
                        onApproveSelected = {
                            quotesViewModel.setApprovedForQuotes(selectedVisibleQuoteIds, true)
                        },
                        onDeleteSelected = {
                            quotesViewModel.deleteQuotes(selectedVisibleQuoteIds)
                        },
                    )
                }
            }
        }
        quoteGroups.forEach { group ->
            item(key = "header-${group.source}", contentType = "quote-section-header") {
                QuoteSectionHeader(
                    source = group.source,
                    count = group.quotes.size,
                    modifier = Modifier.animateItem(),
                )
            }
            if (group.quotes.isEmpty()) {
                item(key = "empty-${group.source}", contentType = "quote-section-empty") {
                    QuoteSectionEmpty(
                        source = group.source,
                        modifier = Modifier.animateItem(),
                    )
                }
            } else {
                items(
                    group.quotes,
                    key = { "${group.source}-${it.id}" },
                    contentType = { "quote-card" },
                ) { quote ->
                    QuoteCard(
                        quote = quote,
                        bookTitle = titlesByBook[quote.bookId] ?: "Unknown book",
                        isSelected = quote.id in selectedQuoteIds,
                        onSelectionChanged = { selected ->
                            quotesViewModel.setQuoteSelected(quote.id, selected)
                        },
                        onToggleWallpaper = { approved ->
                            quotesViewModel.setApprovedForWallpaper(quote.id, approved)
                        },
                        onShareText = {
                            FileSharer.shareQuoteText(
                                context,
                                quote,
                                titlesByBook[quote.bookId] ?: "Unknown book",
                            )
                        },
                        onShareImage = {
                            FileSharer.shareQuoteImage(
                                context,
                                quote,
                                titlesByBook[quote.bookId] ?: "Unknown book",
                            )
                        },
                        onDeleteQuote = { quotesViewModel.deleteQuote(quote.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        if (quoteGroups.isEmpty()) {
            item(key = "quote-filter-empty", contentType = "quote-filter-empty") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(),
                    shape = FlatCardShape,
                    colors = flatCardColors(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Text(
                        text = "No quotes match the current filters. Try another source or book.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A pill chip with a smooth selection transition. Unselected chips are
 * transparent so they never read as grey boxes stacked on the page; only the
 * selected chip receives a solid fill.
 */
@Composable
private fun ModernFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            Color.Transparent
        },
        label = "quoteChipBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "quoteChipContent",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "quoteChipBorder",
    )

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = FlatPillShape,
        color = background,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Primary action surface: a restrained monochrome gradient with a clean
 * rounded-rectangle silhouette and no inner border.
 */
@Composable
private fun GradientActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentColor = MaterialTheme.colorScheme.surface
    val accentColor = MaterialTheme.colorScheme.onSurface
    val gradient = remember(accentColor) {
        Brush.linearGradient(
            colors = listOf(
                accentColor,
                accentColor.copy(alpha = 0.84f),
                accentColor.copy(alpha = 0.96f),
            ),
        )
    }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.42f)
            .clip(FlatControlShape)
            .background(gradient)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Selection toolbar above the quote cards. It selects only the quotes currently
 * visible under the source + book filters and exposes the batch actions.
 */
@Composable
private fun QuoteBatchActions(
    visibleCount: Int,
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAllChanged: (Boolean) -> Unit,
    onApproveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    var confirmBatchDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = FlatCardShape,
        colors = flatCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = onSelectAllChanged,
                    colors = flatCheckboxColors(),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Select all",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "$selectedCount of $visibleCount selected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GradientActionButton(
                    text = "Approve Selected",
                    onClick = onApproveSelected,
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { confirmBatchDelete = true },
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f),
                    shape = FlatControlShape,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error.copy(
                            alpha = if (selectedCount > 0) 0.45f else 0.18f,
                        ),
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Delete Selected",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Delete selected quotes?",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = "This permanently removes $selectedCount quote(s) from your library.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBatchDelete = false
                        onDeleteSelected()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun QuoteSectionHeader(
    source: QuoteSource,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val accent = when (source) {
        QuoteSource.USER -> MaterialTheme.colorScheme.primary
        QuoteSource.AI -> MaterialTheme.colorScheme.tertiary
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = source.sectionTitle,
                style = MaterialTheme.typography.titleSmall,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
        Text(
            text = when (source) {
                QuoteSource.USER -> "Passages you saved yourself while reading."
                QuoteSource.AI -> "Suggestions fetched by AI. Tick one to approve it for wallpapers."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuoteSectionEmpty(
    source: QuoteSource,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = FlatCardShape,
        colors = flatCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = when (source) {
                QuoteSource.USER ->
                    "No user quotes yet. Select text while reading and tap Save Quote."
                QuoteSource.AI ->
                    "No AI quotes yet. Tap \"Fetch AI Quotes\" above to append a batch."
            },
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun WallpaperStudioSection(
    wallpapers: List<TypographyWallpaper>,
    approvedQuotes: List<QuoteEntity>,
    books: List<BookEntity>,
    selectedBookIds: Set<String>,
    isDarkTheme: Boolean,
    currentWallpaperUri: String?,
    selectedFrequency: WallpaperUpdateFrequency,
    onFrequencySelected: (WallpaperUpdateFrequency) -> Unit,
    onGenerateClicked: () -> Unit,
    onSetWallpaper: (TypographyWallpaper) -> Unit,
    onBookSelectionChanged: (Set<String>) -> Unit,
    onThemeChanged: (Boolean) -> Unit,
    onLoadMore: () -> Unit,
    onFetchQuotes: () -> Unit,
    onStopFetchingQuotes: () -> Unit,
    onActivePreviewChanged: (TypographyWallpaper?) -> Unit,
    onManualApplyRequested: (TypographyWallpaper) -> Unit,
    statusMessage: String? = null,
    canGenerate: Boolean = true,
    isGenerating: Boolean = false,
    isLoadingMore: Boolean = false,
    isFetchingQuotes: Boolean = false,
    fetchedQuoteCount: Int = 0,
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

    val pagerState = rememberPagerState(pageCount = { wallpapers.size })
    val activeWallpaper = wallpapers.getOrNull(pagerState.currentPage)
    val manualModeSelected = selectedFrequency == WallpaperUpdateTrigger.OFF

    // Endless carousel: render the next approved wallpaper batch when swiping
    // close to the end.
    LaunchedEffect(pagerState.currentPage, wallpapers.size) {
        if (wallpapers.isNotEmpty() && pagerState.currentPage >= wallpapers.size - 2) {
            onLoadMore()
        }
    }

    // Track the active HorizontalPager page and report it to QuotesViewModel as
    // selectedQuoteForPreview (swipe left/right updates selection).
    LaunchedEffect(pagerState, wallpapers) {
        snapshotFlow { pagerState.currentPage to wallpapers.getOrNull(pagerState.currentPage) }
            .distinctUntilChanged()
            .collect { (_, wallpaper) -> onActivePreviewChanged(wallpaper) }
    }

    var bookMenuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FlatCardShape,
        colors = flatCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "WALLPAPER STUDIO",
                    style = MaterialTheme.typography.titleSmall,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Turn approved quotes into a rotating lock screen canvas. Swipe to preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Book selection dropdown (multi-select) -------------------
            BookSelectionDropdown(
                books = books,
                selectedBookIds = selectedBookIds,
                expanded = bookMenuOpen,
                onExpandedChange = { bookMenuOpen = it },
                onSelectionChanged = onBookSelectionChanged,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // --- Dark / Light canvas theme toggle --------------------------
            // isDarkTheme is reactively collected from WallpaperStudioViewModel
            // (StateFlow) and passed in, so the segmented control and the
            // wallpaper carousel preview re-render immediately on toggle.
            StudioSegmentedControl(
                options = listOf(true, false),
                selected = isDarkTheme,
                onSelected = onThemeChanged,
                label = { isDark -> if (isDark) "Dark Mode" else "Light Mode" },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            GradientActionButton(
                text = when {
                    isFetchingQuotes -> "Stop fetching • ${newQuoteCountLabel(fetchedQuoteCount)}"
                    fetchedQuoteCount > 0 -> "${newQuoteCountLabel(fetchedQuoteCount)} wallpapers created"
                    else -> "Fetch AI Quotes · Endless"
                },
                onClick = if (isFetchingQuotes) onStopFetchingQuotes else onFetchQuotes,
                enabled = isFetchingQuotes || (!isGenerating && books.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(
                visible = isFetchingQuotes,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "New quotes are appended as they arrive. Tap Stop any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Horizontal swipeable carousel bound to available/approved wallpaper
            // previews (rendered from approved quotes; filtered by studio book
            // selection / approval state). Swipe left/right to preview quotes.
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .aspectRatio(9f / 16f),
                shape = RoundedCornerShape(16.dp),
                color = if (isDarkTheme) Color(0xFF121212) else Color(0xFFF8F9FA),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (wallpapers.isNotEmpty()) {
                        HorizontalPager(
                            state = pagerState,
                            // Stable page identity: re-rendering the same quote
                            // for a new theme replaces its bitmap in place.
                            key = { page -> wallpapers[page].id },
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            val wallpaper = wallpapers[page]
                            wallpaper.bitmap?.let { bitmap ->
                                Image(
                                    bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                                    contentDescription = "Wallpaper preview: ${wallpaper.quote.take(30)}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } ?: run {
                                Text(
                                    text = "Loading…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = if (isDarkTheme) Color.White else Color(0xFF111827),
                                )
                            }
                        }
                    } else if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = "Active generated wallpaper",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = if (approvedQuotes.isEmpty()) {
                                "No approved quotes available"
                            } else {
                                "Preparing wallpaper previews…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                            color = if (isDarkTheme) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (wallpapers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                activeWallpaper?.let {
                    val themeLabel = if (isDarkTheme) "Dark" else "Light"
                    Text(
                        text = "${pagerState.currentPage + 1}/${wallpapers.size} • ${it.layoutVariant.label} • $themeLabel theme",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isLoadingMore) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Fetching more quotes…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "AUTOMATIC UPDATE FREQUENCY",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(modifier = Modifier.height(8.dp))
            StudioSegmentedControl(
                options = WallpaperUpdateTrigger.entries,
                selected = selectedFrequency,
                onSelected = onFrequencySelected,
                label = { frequency -> frequency.label },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(18.dp))

            // --- Permanent manual apply action ---------------------------
            val currentPreviewQuote = activeWallpaper
            GradientActionButton(
                text = "Apply Current Wallpaper",
                onClick = {
                    // Apply the exact quote bitmap currently visible in the
                    // active HorizontalPager page (selected Light/Dark mode).
                    val currentWallpaper = wallpapers.getOrNull(pagerState.currentPage)
                    if (currentWallpaper != null) {
                        if (manualModeSelected) {
                            onManualApplyRequested(currentWallpaper)
                        } else {
                            onSetWallpaper(currentWallpaper)
                        }
                    } else {
                        onGenerateClicked()
                    }
                },
                // Enabled whenever there is a valid current preview quote in the
                // carousel; fallback allows generation when no preview yet.
                enabled = currentPreviewQuote != null || (wallpapers.isEmpty() && canGenerate),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(
                visible = !canGenerate && wallpapers.isEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Text(
                    text = "Tick \"Set as Wallpaper\" on an approved quote to enable rendering.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            statusMessage?.let { status ->
                val isError = listOf(
                    "failed",
                    "error",
                    "could not",
                    "please set",
                    "please enter",
                    "timed out",
                    "not configured",
                    "rejected",
                    "all api keys",
                    "out of quota",
                    "missing api key",
                    "model selection",
                    "permission was denied",
                ).any { marker -> status.contains(marker, ignoreCase = true) }
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            AnimatedVisibility(
                visible = isGenerating,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * A borderless segmented control. Unselected options are transparent, so there
 * is no grey track wrapping the selected segment.
 */
@Composable
private fun <T> StudioSegmentedControl(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val segmentBackground by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    Color.Transparent
                },
                label = "wallpaperSegmentBackground",
            )
            val segmentContent by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "wallpaperSegmentContent",
            )
            Surface(
                onClick = { onSelected(option) },
                modifier = Modifier.weight(1f),
                shape = FlatControlShape,
                color = segmentBackground,
                contentColor = segmentContent,
                border = if (isSelected) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookSelectionDropdown(
    books: List<BookEntity>,
    selectedBookIds: Set<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectionChanged: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedBookTitle = books
        .firstOrNull { it.id in selectedBookIds }
        ?.let { FilenameSanitizer.cleanBookTitle(it.title).ifBlank { "Untitled Book" } }
    val selectionLabel = when {
        books.isEmpty() -> "No books in library"
        selectedBookIds.isEmpty() -> "No book picked • latest book used"
        selectedBookIds.size == 1 -> selectedBookTitle ?: "1 book selected"
        else -> "${selectedBookIds.size} books selected"
    }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(FlatControlShape)
                .clickable { onExpandedChange(true) }
                .padding(horizontal = 2.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "BOOKS FOR QUOTES",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = selectionLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "▾",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            if (books.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No books in library yet") },
                    onClick = { onExpandedChange(false) },
                )
            }
            books.forEach { book ->
                val isSelected = book.id in selectedBookIds
                val displayTitle = FilenameSanitizer.cleanBookTitle(book.title)
                    .ifBlank { "Untitled Book" }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = null,
                            colors = flatCheckboxColors(),
                        )
                    },
                    onClick = {
                        onSelectionChanged(
                            if (isSelected) selectedBookIds - book.id else selectedBookIds + book.id,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun QuoteCard(
    quote: QuoteEntity,
    bookTitle: String,
    isSelected: Boolean,
    onSelectionChanged: (Boolean) -> Unit,
    onToggleWallpaper: (Boolean) -> Unit,
    onShareText: () -> Unit,
    onShareImage: () -> Unit,
    onDeleteQuote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (quote.source) {
        QuoteSource.USER -> MaterialTheme.colorScheme.primary
        QuoteSource.AI -> MaterialTheme.colorScheme.tertiary
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = FlatCardShape,
        colors = flatCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onSelectionChanged,
                    colors = flatCheckboxColors(),
                )
                Text(
                    text = if (isSelected) "Selected" else "Select",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = quote.source.badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = accent,
                )
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "“${quote.selectedText}”",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "— $bookTitle",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Checkbox(
                        checked = quote.isApprovedForWallpaper,
                        onCheckedChange = onToggleWallpaper,
                        colors = flatCheckboxColors(),
                    )
                    Text(
                        text = "Set as Wallpaper",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = if (quote.isApprovedForWallpaper) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onShareText,
                    modifier = Modifier.weight(1f),
                    shape = FlatControlShape,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Share text",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onShareImage,
                    modifier = Modifier.weight(1f),
                    shape = FlatControlShape,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Share image",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onDeleteQuote) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Keeps the live fetch counter readable during an endless session:
 * `5 new`, `12 new`, `20 new`, then `30+ new` once the batch is large.
 */
private fun newQuoteCountLabel(count: Int): String = when {
    count <= 0 -> "0 new"
    count >= 30 -> "30+ new"
    else -> "$count new"
}

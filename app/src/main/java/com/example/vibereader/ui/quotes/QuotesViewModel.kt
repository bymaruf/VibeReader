package com.example.vibereader.ui.quotes

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.document.EBookDocumentLoader
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.local.WallpaperTarget
import com.example.vibereader.data.local.WallpaperUpdateTrigger
import com.example.vibereader.data.repository.AiConfigurationException
import com.example.vibereader.data.repository.AiFailoverManager
import com.example.vibereader.data.repository.AiQuoteRepository
import com.example.vibereader.data.repository.AiRequestException
import com.example.vibereader.data.repository.QuoteRepository
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.data.room.QuoteSource
import com.example.vibereader.domain.model.EBookDocument
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.model.EBookMetadata
import com.example.vibereader.domain.model.TypographyWallpaper
import com.example.vibereader.service.WallpaperRotationManager
import com.example.vibereader.util.FilenameSanitizer
import com.example.vibereader.util.TypographyWallpaperGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Quick filters for the quote library. */
enum class QuoteFilter(val label: String) {
    ALL("All"),
    USER("Yours"),
    AI("AI"),
    APPROVED("Approved"),
}

/** One visual section in the quote library. */
data class QuoteGroup(
    val source: QuoteSource,
    val quotes: List<QuoteEntity>,
)

val QuoteSource.sectionTitle: String
    get() = when (this) {
        QuoteSource.USER -> "YOUR QUOTES"
        QuoteSource.AI -> "AI-FETCHED QUOTES"
    }

val QuoteSource.badgeLabel: String
    get() = when (this) {
        QuoteSource.USER -> "YOUR HIGHLIGHT"
        QuoteSource.AI -> "AI CURATED"
    }

/**
 * Owns the quote library: observes persisted user/AI separation, performs
 * human-in-the-loop approvals and batch operations, and runs the endless AI
 * fetch loop.
 *
 * AI fetching is delegated to [AiQuoteRepository], which resolves the active
 * provider/key/model, then uses [AiFailoverManager] to try every saved
 * provider/key until one succeeds. [onQuotePersisted] fires after each Room
 * insert so [newQuotesFetchedCount] grows in real time.
 *
 * Every AI failure is logged with the `VibeReader_AI` tag, shown inline through
 * [message], and force-emitted as a Toast. [isFetchingQuotes] is always reset
 * in `finally`, even when the whole provider pool fails.
 */
class QuotesViewModel(
    context: Context,
    private val database: VibeReaderDatabase,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wallpaperPreferences = WallpaperPreferences(appContext)
    private val quoteRepository = QuoteRepository(database)
    private val documentLoader = EBookDocumentLoader(appContext)
    private val aiRepository = AiQuoteRepository(appContext, database)

    /** Master quote list from Room (append-only; never cleared by AI fetches). */
    val quotes: StateFlow<List<QuoteEntity>> = quoteRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val books: StateFlow<List<BookEntity>> = database.bookDao().observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _filter = MutableStateFlow(QuoteFilter.ALL)
    val filter: StateFlow<QuoteFilter> = _filter.asStateFlow()

    private val _sortNewestFirst = MutableStateFlow(true)
    val sortNewestFirst: StateFlow<Boolean> = _sortNewestFirst.asStateFlow()

    private val _selectedBookIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedBookIds: StateFlow<Set<String>> = _selectedBookIds.asStateFlow()

    /** Ids currently checked in the library (scoped to the visible list). */
    private val _selectedQuoteIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedQuoteIds: StateFlow<Set<Long>> = _selectedQuoteIds.asStateFlow()

    /** True while the endless fetch loop is running. Always reset in `finally`. */
    private val _isFetchingQuotes = MutableStateFlow(false)
    val isFetchingQuotes: StateFlow<Boolean> = _isFetchingQuotes.asStateFlow()

    /**
     * Count of NEW quotes persisted in the current/last fetch session. Reset to
     * 0 when the user explicitly taps Stop or starts a new fetching session, and
     * incremented in real time for each successful Room insert.
     */
    private val _newQuotesFetchedCount = MutableStateFlow(0)
    val newQuotesFetchedCount: StateFlow<Int> = _newQuotesFetchedCount.asStateFlow()

    // Backward-compatible alias used by existing UI bindings.
    val fetchedCount: StateFlow<Int> = newQuotesFetchedCount

    /**
     * The quote currently previewed/selected in the Wallpaper Studio carousel
     * (active HorizontalPager page). Updated when the user swipes the carousel.
     */
    private val _selectedQuoteForPreview = MutableStateFlow<QuoteEntity?>(null)
    val selectedQuoteForPreview: StateFlow<QuoteEntity?> = _selectedQuoteForPreview.asStateFlow()

    /** Human-readable status / error text shown by the UI and as a Toast. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Emits after a batch approval/delete or a new approved insert so the
     * Wallpaper Studio carousel can append/drop previews immediately.
     */
    private val _quoteEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val quoteEvents = _quoteEvents.asSharedFlow()

    private var fetchJob: Job? = null

    /** Quotes filtered, sorted, and grouped with "Yours" always above "AI". */
    val groupedQuotes: StateFlow<List<QuoteGroup>> = combine(
        quotes,
        filter,
        sortNewestFirst,
        selectedBookIds,
    ) { allQuotes, activeFilter, newestFirst, bookIds ->
        val bookFiltered = if (bookIds.isEmpty()) {
            allQuotes
        } else {
            allQuotes.filter { it.bookId in bookIds }
        }
        val visible = when (activeFilter) {
            QuoteFilter.ALL -> bookFiltered
            QuoteFilter.USER -> bookFiltered.filter { it.source == QuoteSource.USER }
            QuoteFilter.AI -> bookFiltered.filter { it.source == QuoteSource.AI }
            QuoteFilter.APPROVED -> bookFiltered.filter { it.isApprovedForWallpaper }
        }
        val sorted = if (newestFirst) {
            visible.sortedByDescending(QuoteEntity::createdAt)
        } else {
            visible.sortedBy(QuoteEntity::createdAt)
        }
        listOf(QuoteSource.USER, QuoteSource.AI).mapNotNull { source ->
            val groupQuotes = sorted.filter { it.source == source }
            if (activeFilter != QuoteFilter.ALL && groupQuotes.isEmpty()) {
                null
            } else {
                QuoteGroup(source, groupQuotes)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = listOf(
            QuoteGroup(QuoteSource.USER, emptyList()),
            QuoteGroup(QuoteSource.AI, emptyList()),
        ),
    )

    // -------------------------------------------------------------------------------------
    // Filters / batch selection
    // -------------------------------------------------------------------------------------

    fun setFilter(filter: QuoteFilter) {
        _filter.value = filter
    }

    fun toggleBookFilter(bookId: String) {
        _selectedBookIds.update { current ->
            if (bookId in current) current - bookId else current + bookId
        }
    }

    fun clearBookFilters() {
        _selectedBookIds.value = emptySet()
    }

    fun toggleSortOrder() {
        _sortNewestFirst.update { !it }
    }

    fun setQuoteSelected(quoteId: Long, selected: Boolean) {
        _selectedQuoteIds.update { current ->
            if (selected) current + quoteId else current - quoteId
        }
    }

    fun selectAllVisible(visibleIds: Set<Long>) {
        _selectedQuoteIds.update { current -> current + visibleIds }
    }

    fun clearSelection() {
        _selectedQuoteIds.value = emptySet()
    }

    /**
     * Human-in-the-loop approval gate (stage 1): a quote becomes wallpaper
     * material only when the user explicitly ticks "Set as Wallpaper".
     * Existing approvals are never cleared by AI fetches.
     */
    fun setApprovedForWallpaper(quoteId: Long, approved: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                quoteRepository.setApprovedForWallpaper(quoteId, approved)
                _quoteEvents.tryEmit(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                postUiError("Could not update quote approval.", error)
            }
        }
    }

    fun setApprovedForQuotes(quoteIds: Set<Long>, approved: Boolean) {
        if (quoteIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.withTransaction {
                    quoteIds.forEach { id ->
                        quoteRepository.setApprovedForWallpaper(id, approved)
                    }
                }
                clearSelection()
                _quoteEvents.tryEmit(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                postUiError("Could not update the selected quotes.", error)
            }
        }
    }

    fun deleteQuote(quoteId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                quoteRepository.deleteById(quoteId)
                _selectedQuoteIds.update { it - quoteId }
                _quoteEvents.tryEmit(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                postUiError("Could not delete the quote.", error)
            }
        }
    }

    fun deleteQuotes(quoteIds: Set<Long>) {
        if (quoteIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.withTransaction {
                    quoteIds.forEach { id -> quoteRepository.deleteById(id) }
                }
                _selectedQuoteIds.update { it - quoteIds }
                _quoteEvents.tryEmit(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                postUiError("Could not delete the selected quotes.", error)
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Endless AI quote fetching. Wallpaper Studio passes approveForWallpaper=true,
    // so every valid inserted quote becomes a carousel card immediately.
    // -------------------------------------------------------------------------------------

    fun startFetchingQuotes(bookIds: Set<String>) {
        if (_isFetchingQuotes.value) return
        fetchJob?.cancel()
        _newQuotesFetchedCount.value = 0
        _message.value = null
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            _isFetchingQuotes.value = true
            try {
                val books = resolveBooks(bookIds)
                if (books.isEmpty()) {
                    postUiError(
                        message = "Open a book or pick one for quotes before fetching.",
                        exception = null,
                        toast = true,
                    )
                    return@launch
                }
                var roundRobinIndex = 0
                val zeroYieldAttempts = mutableMapOf<String, Int>()

                while (isActive) {
                    val availableBooks = books.filter { book ->
                        (zeroYieldAttempts[book.id] ?: 0) < MAX_ZERO_YIELD_ATTEMPTS
                    }
                    if (availableBooks.isEmpty()) {
                        postUiError(
                            "No new quotes found after $MAX_ZERO_YIELD_ATTEMPTS attempts " +
                                "per book. Try another book or check your AI API key in Settings.",
                            exception = null,
                            toast = true,
                        )
                        return@launch
                    }
                    val book = availableBooks[roundRobinIndex % availableBooks.size]
                    roundRobinIndex++
                    val title = displayTitle(book)
                    val author = book.author?.takeIf(String::isNotBlank)

                    // Walk several non-overlapping chapters/page windows per
                    // book so one session produces a rich wallpaper queue
                    // while the counter ticks after every chunk.
                    for (chunkIndex in 0 until CHUNKS_PER_BOOK) {
                        if (!isActive) return@launch
                        val attemptsSoFar = zeroYieldAttempts[book.id] ?: 0
                        if (attemptsSoFar >= MAX_ZERO_YIELD_ATTEMPTS) break
                        val existing = database.quoteDao().observeAll()
                            .first()
                            .asSequence()
                            .filter { it.bookId == book.id }
                            .map { it.selectedText }
                            .toList()

                        try {
                            val result = aiRepository.fetchAndPersistQuotes(
                                bookId = book.id,
                                bookTitle = title,
                                bookAuthor = author,
                                chapter = null,
                                batchSize = AiQuoteRepository.DEFAULT_BATCH_SIZE,
                                alreadyCollected = existing,
                                approveForWallpaper = true,
                                chunkIndex = chunkIndex,
                                chunkCount = CHUNKS_PER_BOOK,
                                // Fire immediately after every real Room insert
                                // so the counter and carousel tick during the
                                // chunk, not only after the whole batch.
                                onQuotePersisted = {
                                    _newQuotesFetchedCount.update { current -> current + 1 }
                                    _quoteEvents.tryEmit(Unit)
                                },
                            )
                            if (result.insertedCount > 0) {
                                zeroYieldAttempts.remove(book.id)
                                postInlineMessage(
                                    "Fetched ${result.insertedCount} new quote(s) from " +
                                        "$title · total ${_newQuotesFetchedCount.value}. " +
                                        "Existing approvals kept.",
                                )
                            } else {
                                val attempts = attemptsSoFar + 1
                                zeroYieldAttempts[book.id] = attempts
                                if (attempts < MAX_ZERO_YIELD_ATTEMPTS) {
                                    postInlineMessage(
                                        "No new quotes from $title " +
                                            "(attempt $attempts/$MAX_ZERO_YIELD_ATTEMPTS); " +
                                            "trying another section…",
                                    )
                                } else {
                                    postInlineMessage(
                                        "No new quotes from $title after " +
                                            "$MAX_ZERO_YIELD_ATTEMPTS attempts; skipping it.",
                                    )
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (config: AiConfigurationException) {
                            // Missing key/model — surface clearly and stop the loop.
                            Log.e(AI_ERROR_TAG, "Execution error", config)
                            postUiError(config.message, config, toast = true)
                            return@launch
                        } catch (request: AiRequestException) {
                            logRequestError(request)
                            if (request.stopBackgroundFetch) {
                                // Auth/quota/timeout after the whole healthy pool
                                // was tried. Stop cleanly instead of spinning.
                                postUiError(request.toUiMessage(), request, toast = true)
                                return@launch
                            }
                            // A model returned unusable output, not an exhausted
                            // key. Keep this book moving through its other chunks.
                            postInlineMessage(
                                "AI returned no usable quotes for one section of " +
                                    "$title; trying another section…",
                            )
                        } catch (error: Throwable) {
                            // Network, JSON, or unexpected failures must never hang
                            // the UI. One bad book must not abort every selected book.
                            Log.e(AI_ERROR_TAG, "Execution error", error)
                            val attempts = attemptsSoFar + 1
                            zeroYieldAttempts[book.id] = attempts
                            postInlineMessage(
                                "Could not read a quote section of $title " +
                                    "(${error.localizedMessage ?: "unknown error"}); " +
                                    "trying another section…",
                            )
                        }
                        if (!isActive) return@launch
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(AI_ERROR_TAG, "Execution error", error)
                postUiError(
                    error.localizedMessage ?: "Fetching quotes failed unexpectedly.",
                    error,
                    toast = true,
                )
            } finally {
                _isFetchingQuotes.value = false
            }
        }
    }

    /**
     * Stops the endless fetch loop. The live session count is preserved so the
     * button can report how many wallpapers were created; the next explicit
     * fetch starts a fresh session from 0.
     */
    fun stopFetchingQuotes() {
        fetchJob?.cancel()
        fetchJob = null
        _isFetchingQuotes.value = false
        val created = _newQuotesFetchedCount.value
        _message.value = if (created > 0) {
            "Stopped fetching · $created new wallpapers created."
        } else {
            "Stopped fetching quotes."
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Tracks the active carousel page selection from Wallpaper Studio. */
    fun selectQuoteForPreview(quote: QuoteEntity?) {
        _selectedQuoteForPreview.value = quote
    }

    /**
     * Manual mode apply: render the exact quote/theme currently shown in the
     * preview carousel, set it as the wallpaper via [WallpaperManager.setBitmap],
     * persist the preview path, and cancel all auto-rotation WorkManager jobs so
     * the manual wallpaper remains fixed.
     */
    fun applyManualWallpaper(wallpaper: TypographyWallpaper, isDarkMode: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Manual frequency: stop/cancel automatic rotation jobs.
                val latest = wallpaperPreferences.getSettings()
                val manualSettings = latest.copy(
                    updateTrigger = WallpaperUpdateTrigger.OFF,
                    isWallpaperEnabled = false,
                    isDarkTheme = isDarkMode,
                )
                wallpaperPreferences.saveSettings(manualSettings)
                WallpaperRotationManager.updateFrequency(appContext, WallpaperUpdateTrigger.OFF)

                // Render the exact quote and selected theme shown in preview.
                val bitmap = TypographyWallpaperGenerator.generateWallpaperBitmap(
                    context = appContext,
                    quote = wallpaper.quote,
                    bookTitle = wallpaper.bookTitle,
                    progressText = wallpaper.progressText.orEmpty(),
                    bookAuthor = wallpaper.bookAuthor,
                    layoutVariant = wallpaper.layoutVariant,
                    isDarkMode = isDarkMode,
                )
                val wallpaperManager = WallpaperManager.getInstance(appContext)
                val target = wallpaperPreferences.getSettings().applyTarget
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    when (target) {
                        WallpaperTarget.HOMESCREEN ->
                            wallpaperManager.setBitmap(
                                bitmap,
                                null,
                                true,
                                WallpaperManager.FLAG_SYSTEM,
                            )

                        WallpaperTarget.LOCKSCREEN ->
                            wallpaperManager.setBitmap(
                                bitmap,
                                null,
                                true,
                                WallpaperManager.FLAG_LOCK,
                            )

                        WallpaperTarget.BOTH -> {
                            wallpaperManager.setBitmap(
                                bitmap,
                                null,
                                true,
                                WallpaperManager.FLAG_SYSTEM,
                            )
                            wallpaperManager.setBitmap(
                                bitmap,
                                null,
                                true,
                                WallpaperManager.FLAG_LOCK,
                            )
                        }
                    }
                } else {
                    wallpaperManager.setBitmap(bitmap)
                }
                val wallpaperFile = TypographyWallpaperGenerator.saveWallpaperPreview(
                    context = appContext,
                    bitmap = bitmap,
                )
                wallpaperPreferences.saveCurrentWallpaperPath(wallpaperFile.absolutePath)
                postInlineMessage("Wallpaper applied.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                Log.e(AI_ERROR_TAG, "Execution error", error)
                postUiError(
                    "Wallpaper permission was denied. Allow VibeReader to set wallpapers and try again.",
                    error,
                    toast = true,
                )
            } catch (error: Throwable) {
                Log.e(AI_ERROR_TAG, "Execution error", error)
                postUiError(
                    error.localizedMessage ?: "Could not apply the wallpaper.",
                    error,
                    toast = true,
                )
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------------------

    private suspend fun resolveBooks(ids: Set<String>): List<BookEntity> {
        val allBooks = database.bookDao().observeAll().first()
        if (allBooks.isEmpty()) return emptyList()
        if (ids.isEmpty()) return listOf(allBooks.first())
        val selected = ids.mapNotNull { id -> allBooks.firstOrNull { it.id == id } }
        return selected.ifEmpty { listOf(allBooks.first()) }
    }

    private fun displayTitle(book: BookEntity): String =
        FilenameSanitizer.cleanBookTitle(book.title).ifBlank { "Untitled Book" }

    private fun postInlineMessage(text: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) { _message.value = text }
    }

    private fun postUiError(message: String?, exception: Throwable?, toast: Boolean = false) {
        val resolved = message?.takeIf { it.isNotBlank() }
            ?: AiFailoverManager.ALL_KEYS_FAILED_MESSAGE
        viewModelScope.launch(Dispatchers.Main.immediate) {
            _message.value = resolved
            if (toast) {
                Toast.makeText(appContext, resolved, Toast.LENGTH_LONG).show()
            }
        }
        if (toast) {
            // Also post through the main handler so the toast shows even if the
            // ViewModel scope is in an unexpected state.
            mainHandler.post {
                Toast.makeText(appContext, resolved, Toast.LENGTH_LONG).show()
            }
        }
        if (exception != null) {
            Log.e(AI_ERROR_TAG, "Execution error", exception)
        }
    }

    private fun logRequestError(request: AiRequestException) {
        val status = request.statusCode?.let { " status=$it" }.orEmpty()
        val provider = request.provider?.shortLabel ?: request.provider?.displayName ?: "AI"
        Log.e(
            AI_ERROR_TAG,
            "provider=$provider model=${request.model.orEmpty()}$status message=${request.message}",
            request,
        )
    }

    private fun AiRequestException.toUiMessage(): String = when {
        statusCode == 401 || statusCode == 403 ->
            "API key rejected. Check your AI API key in Settings."

        statusCode == 429 -> "Rate limited — try again shortly."
        statusCode != null && statusCode in 500..599 ->
            "AI provider is unavailable right now. Try again shortly."

        statusCode != null ->
            "${provider?.shortLabel ?: provider?.displayName ?: "AI"} failed (HTTP $statusCode): " +
                (message ?: "request error")

        message?.contains("timed out", ignoreCase = true) == true ->
            "AI request timed out. Check your internet connection and try again."

        message == AiFailoverManager.ALL_KEYS_FAILED_MESSAGE -> message.orEmpty()
        else -> "${provider?.shortLabel ?: provider?.displayName ?: "AI"} error: " +
            (message ?: "request failed")
    }

    class Factory(
        private val context: Context,
        private val database: VibeReaderDatabase,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(QuotesViewModel::class.java)) {
                return QuotesViewModel(context, database) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }

    private companion object {
        const val AI_ERROR_TAG = "VibeReader_AI"

        /**
         * One full eight-chunk pass over a book before it is parked. The old
         * value of 3 skipped the fourth chunk, so a book could never be fully
         * sampled even though four chunks were requested.
         */
        const val MAX_ZERO_YIELD_ATTEMPTS = 8
        const val CHUNKS_PER_BOOK = 8
    }
}

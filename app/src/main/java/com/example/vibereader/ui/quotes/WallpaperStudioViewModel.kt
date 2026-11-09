package com.example.vibereader.ui.quotes

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.document.EBookDocumentLoader
import com.example.vibereader.data.local.WallpaperPreferences
import com.example.vibereader.data.repository.QuoteRepository
import com.example.vibereader.domain.QuoteQualityValidator
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.data.room.HighlightEntity
import com.example.vibereader.data.room.QuoteEntity
import com.example.vibereader.domain.model.EBookDocument
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.model.TypographyWallpaper
import com.example.vibereader.domain.model.WallpaperLayoutVariant
import com.example.vibereader.domain.model.WallpaperTheme
import com.example.vibereader.util.FilenameSanitizer
import com.example.vibereader.util.TypographyWallpaperGenerator
import com.example.vibereader.util.WallpaperApplyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class WallpaperStudioViewModel(
    context: Context,
    private val database: VibeReaderDatabase,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val documentLoader = EBookDocumentLoader(appContext)
    private val preferences = WallpaperPreferences(appContext)
    private val quoteRepository = QuoteRepository(database)

    private val _wallpapers = MutableStateFlow<List<TypographyWallpaper>>(emptyList())
    val wallpapers = _wallpapers.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _currentBook = MutableStateFlow<BookEntity?>(null)
    val currentBook = _currentBook.asStateFlow()

    /** Books the user picked in the Quotes screen dropdown. */
    private val _selectedBookIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedBookIds = _selectedBookIds.asStateFlow()

    /** Dark (black background/white text) or light (white background/black text). */
    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode = _isDarkMode.asStateFlow()

    /** Keeps the layout variant cycling across batches and load-more pages. */
    private var nextVariantIndex = 0

    /** Latest theme re-render job + request id, used to discard stale renders. */
    private var themeRenderJob: Job? = null
    private var themeRenderRequestId = 0

    /** Serializes approval syncs so rapid checkbox taps cannot lose updates. */
    private val approvalSyncMutex = Mutex()

    /** Round-robin cursor for "load more" across the selected books. */
    private var loadMoreCursor = 0

    init {
        val settings = preferences.getSettings()
        _isDarkMode.value = settings.isDarkTheme
        _selectedBookIds.value = settings.selectedBookIds
        loadCurrentBook()
    }

    fun loadCurrentBook() {
        viewModelScope.launch {
            try {
                _error.value = null
                val selected = _selectedBookIds.value
                if (selected.isNotEmpty()) {
                    // First book still present in the library wins.
                    val book = selected.firstNotNullOfOrNull { database.bookDao().getById(it) }
                    if (book != null) {
                        _currentBook.value = book
                        return@launch
                    }
                }
                val savedQuotes = quoteRepository.observeAll().first()
                val savedHighlights = database.highlightDao().observeAll().first()
                val savedText = newestContent(savedQuotes.firstOrNull(), savedHighlights.firstOrNull())
                    ?: return@launch

                val book = database.bookDao().getById(savedText.bookId)
                    ?: return@launch
                _currentBook.value = book
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Could not load current book."
            }
        }
    }

    fun selectBooks(ids: Set<String>) {
        _selectedBookIds.value = ids
        try {
            preferences.saveSettings(preferences.getSettings().copy(selectedBookIds = ids))
        } catch (_: Exception) {
            // Preferences are best-effort; the in-memory selection still works.
        }
        viewModelScope.launch {
            val book = ids.firstNotNullOfOrNull { database.bookDao().getById(it) }
            if (book != null) _currentBook.value = book
        }
    }

    /**
     * Single source of truth for the wallpaper canvas theme. Updates the state
     * synchronously, persists it for background workers, then immediately
     * re-renders every page currently loaded in the carousel with the new
     * palette. Stale renders are discarded if the user toggles again.
     */
    fun setWallpaperTheme(isDark: Boolean) {
        if (_isDarkMode.value == isDark) return
        _isDarkMode.value = isDark
        try {
            // Persist only this key so a stale full-settings snapshot can never
            // overwrite the theme that background workers will use.
            preferences.saveTheme(isDark)
        } catch (_: Exception) {
            // Preferences are best-effort; the in-memory state still applies.
        }

        val requestId = ++themeRenderRequestId
        themeRenderJob?.cancel()
        themeRenderJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            try {
                val current = _wallpapers.value
                if (current.isEmpty()) {
                    // The Studio may only be showing the last applied
                    // `current.jpg` fallback. Render one approved quote with
                    // the new palette so the visible preview changes too.
                    val firstApproved = quoteRepository.observeApproved()
                        .first()
                        .firstOrNull { QuoteQualityValidator.isValidQuote(it.selectedText) }
                    val preview = firstApproved?.let { quote ->
                        buildWallpaperForQuote(quote, isDark)
                    }
                    if (requestId == themeRenderRequestId && _isDarkMode.value == isDark) {
                        _wallpapers.value = listOfNotNull(preview)
                    }
                    return@launch
                }
                // Render and swap one page at a time. This keeps peak memory at
                // (existing pages + one new bitmap) instead of holding two full
                // carousels at once, which matters for full-screen bitmaps.
                for (wallpaper in current) {
                    if (requestId != themeRenderRequestId || _isDarkMode.value != isDark) break
                    val rendered = reRenderWallpaper(wallpaper, isDark)
                    if (requestId != themeRenderRequestId || _isDarkMode.value != isDark) break
                    _wallpapers.value = _wallpapers.value.map { item ->
                        if (item.id == wallpaper.id) rendered else item
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (requestId == themeRenderRequestId) {
                    _error.value = e.localizedMessage
                        ?: "Could not re-render wallpaper batch."
                }
            } finally {
                if (requestId == themeRenderRequestId) {
                    _isGenerating.value = false
                }
            }
        }
    }

    /** Convenience overload for callers that simply flip the current theme. */
    fun toggleTheme(isDark: Boolean = !_isDarkMode.value) {
        setWallpaperTheme(isDark)
    }

    /** Re-renders one carousel page from its quote metadata using [isDark]. */
    private suspend fun reRenderWallpaper(
        wallpaper: TypographyWallpaper,
        isDark: Boolean,
    ): TypographyWallpaper {
        val bitmap = TypographyWallpaperGenerator.generateWallpaperBitmap(
            context = appContext,
            quote = wallpaper.quote,
            bookTitle = wallpaper.bookTitle,
            progressText = wallpaper.progressText.orEmpty(),
            bookAuthor = wallpaper.bookAuthor,
            layoutVariant = wallpaper.layoutVariant,
            isDarkMode = isDark,
        )
        return wallpaper.copy(
            theme = if (isDark) WallpaperTheme.DARK else WallpaperTheme.LIGHT,
            bitmap = bitmap,
        )
    }

    /**
     * Guarantees a batch entering the pager matches the live theme. A slow
     * render can overlap a theme flip, so only mismatched pages are redrawn.
     */
    private suspend fun ensureCurrentTheme(
        wallpapers: List<TypographyWallpaper>,
    ): List<TypographyWallpaper> {
        if (wallpapers.isEmpty()) return wallpapers
        val isDark = _isDarkMode.value
        val expectedTheme = if (isDark) WallpaperTheme.DARK else WallpaperTheme.LIGHT
        if (wallpapers.all { it.theme == expectedTheme }) return wallpapers
        return wallpapers.map { wallpaper ->
            if (wallpaper.theme == expectedTheme) wallpaper
            else reRenderWallpaper(wallpaper, isDark)
        }
    }

    fun generateBatch(batchSize: Int = DEFAULT_BATCH_SIZE) {
        viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            try {
                generateBatchInternal(batchSize)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Never keep stale wallpapers that may belong to quotes the
                // user has just un-approved.
                _wallpapers.value = emptyList()
                _error.value = e.localizedMessage ?: "Failed to generate wallpaper batch."
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /**
     * Keeps the carousel in sync with the human-in-the-loop approval gate:
     * drop wallpapers whose quote was un-approved, immediately append a
     * preview for a newly approved quote, and rebuild the batch when the
     * carousel became empty.
     */
    fun syncWallpapersWithApprovals() {
        viewModelScope.launch {
            approvalSyncMutex.withLock {
                if (_isGenerating.value) return@withLock
                val approvedQuotes = quoteRepository.observeApproved().first()
                val approvedTexts = approvedQuotes
                    .map { it.selectedText }
                    .filter(QuoteQualityValidator::isValidQuote)
                    .toSet()
                val filtered = _wallpapers.value.filter { it.quote in approvedTexts }
                if (approvedTexts.isEmpty()) {
                    _wallpapers.value = emptyList()
                    return@withLock
                }
                if (filtered.isEmpty()) {
                    // First approval: render just that quote (one page).
                    // Generating eight duplicates of the same quote is what
                    // made the carousel feel stagnant; further approvals are
                    // appended one by one below.
                    val firstApproved = approvedQuotes.firstOrNull {
                        it.selectedText in approvedTexts
                    }
                    val wallpaper = firstApproved?.let { quote ->
                        try {
                            buildWallpaperForQuote(quote, _isDarkMode.value)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            _error.value = error.localizedMessage
                                ?: "Could not render the approved quote."
                            null
                        }
                    }
                    _wallpapers.value = ensureCurrentTheme(listOfNotNull(wallpaper))
                    return@withLock
                }

                // Approving a second quote should add it to the carousel right
                // away instead of waiting for a manual refresh. The cap
                // prevents 25 approvals from allocating 25 full-screen
                // bitmaps and risking an OutOfMemoryError.
                if (filtered.size >= MAX_CAROUSEL_WALLPAPERS) {
                    _wallpapers.value = filtered
                    return@withLock
                }
                val represented = filtered.map { it.quote }.toSet()
                val newlyApproved = approvedQuotes.firstOrNull {
                    it.selectedText in approvedTexts && it.selectedText !in represented
                }
                val appended = newlyApproved?.let { quote ->
                    try {
                        buildWallpaperForQuote(quote, _isDarkMode.value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _error.value = error.localizedMessage
                            ?: "Could not render the newly approved quote."
                        null
                    }
                }
                _wallpapers.value = filtered + ensureCurrentTheme(listOfNotNull(appended))
            }
        }
    }

    /** Builds one carousel preview for a single approved quote. */
    private suspend fun buildWallpaperForQuote(
        quote: QuoteEntity,
        isDark: Boolean,
    ): TypographyWallpaper? = withContext(Dispatchers.Default) {
        if (!QuoteQualityValidator.isValidQuote(quote.selectedText)) {
            return@withContext null
        }
        val book = database.bookDao().getById(quote.bookId) ?: return@withContext null
        val document = loadBookDocument(book)
        val variant = WallpaperLayoutVariant.entries[
            nextVariantIndex % WallpaperLayoutVariant.entries.size
        ]
        val variantIndex = nextVariantIndex
        nextVariantIndex++
        val progress = getProgressText(book)
        val bitmap = TypographyWallpaperGenerator.generateWallpaperBitmap(
            context = appContext,
            quote = quote.selectedText,
            bookTitle = document.title,
            progressText = progress,
            bookAuthor = document.author,
            layoutVariant = variant,
            isDarkMode = isDark,
        )
        TypographyWallpaper(
            id = "${book.id}-w$variantIndex",
            quote = quote.selectedText,
            bookTitle = document.title,
            bookAuthor = document.author,
            progressText = progress,
            designIndex = variantIndex,
            layoutVariant = variant,
            theme = if (isDark) WallpaperTheme.DARK else WallpaperTheme.LIGHT,
            bitmap = bitmap,
        )
    }

    /**
     * Endless carousel support: renders additional approved-quote wallpapers
     * (round-robin over the selected books) and appends them when the user
     * swipes close to the end of the list.
     */
    fun fetchNextWallpaperBatch() = loadMoreWallpapers()

    fun loadMoreWallpapers() {
        if (_isLoadingMore.value || _isGenerating.value) return
        viewModelScope.launch {
            if (_wallpapers.value.isEmpty()) return@launch
            if (_wallpapers.value.size >= MAX_CAROUSEL_WALLPAPERS) return@launch
            _isLoadingMore.value = true
            _error.value = null
            try {
                val books = resolveTargetBooks()
                if (books.isEmpty()) return@launch
                val book = books[loadMoreCursor % books.size]
                val added = ensureCurrentTheme(
                    buildWallpapersForBook(
                        book = book,
                        count = LOAD_MORE_COUNT,
                        isDark = _isDarkMode.value,
                    ),
                )
                _wallpapers.value = _wallpapers.value + added
                loadMoreCursor++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Could not fetch more wallpaper quotes."
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun setActiveWallpaper(wallpaper: TypographyWallpaper) {
        viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            try {
                // Always render from the live theme state instead of trusting
                // a potentially stale cached bitmap. This is what guarantees
                // the system wallpaper matches the toggle.
                var appliedTheme = _isDarkMode.value
                var rendered = withContext(Dispatchers.Default) {
                    reRenderWallpaper(wallpaper, appliedTheme)
                }
                val latestTheme = _isDarkMode.value
                if (latestTheme != appliedTheme) {
                    appliedTheme = latestTheme
                    rendered = withContext(Dispatchers.Default) {
                        reRenderWallpaper(wallpaper, appliedTheme)
                    }
                }
                val bitmap = rendered.bitmap
                    ?: throw IOException("Wallpaper bitmap is not available.")
                WallpaperApplyManager.applyWallpaper(
                    context = appContext,
                    bitmap = bitmap,
                    target = preferences.getSettings().applyTarget,
                ).getOrThrow()
                val wallpaperFile = TypographyWallpaperGenerator.saveWallpaperPreview(
                    context = appContext,
                    bitmap = bitmap,
                )
                preferences.saveCurrentWallpaperPath(wallpaperFile.absolutePath)
                // Keep the carousel preview identical to what was applied.
                _wallpapers.value = _wallpapers.value.map { item ->
                    if (item.id == rendered.id) rendered else item
                }
                _error.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                _error.value = "Wallpaper permission was denied. Allow VibeReader to set wallpapers and try again."
            } catch (error: Throwable) {
                _error.value = error.localizedMessage ?: "Could not set wallpaper."
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private suspend fun generateBatchInternal(batchSize: Int) {
        val books = resolveTargetBooks()
        if (books.isEmpty()) {
            throw IOException("Pick a book or save a quote while reading first.")
        }
        val generated = mutableListOf<TypographyWallpaper>()
        var round = 0
        while (generated.size < batchSize) {
            val remaining = batchSize - generated.size
            val perBook = if (books.size == 1) remaining else (remaining / books.size).coerceAtLeast(1)
            for (book in books) {
                if (generated.size >= batchSize) break
                val count = perBook.coerceAtMost(batchSize - generated.size)
                try {
                    generated += buildWallpapersForBook(
                        book = book,
                        count = count,
                        isDark = _isDarkMode.value,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // This book has no approved quotes yet; try the next one.
                }
            }
            round++
            if (round > RETRY_ROUNDS) break
        }
        if (generated.isEmpty()) {
            throw IOException(
                "Approve at least one quote (Set as Wallpaper) before rendering.",
            )
        }
        _wallpapers.value = ensureCurrentTheme(generated)
    }

    private suspend fun buildWallpapersForBook(
        book: BookEntity,
        count: Int,
        isDark: Boolean,
    ): List<TypographyWallpaper> = withContext(Dispatchers.Default) {
        val document = loadBookDocument(book)
        // Human-in-the-loop stage 2: render ONLY user-approved quotes.
        val usableQuotes = approvedQuotesFor(book.id)
        if (usableQuotes.isEmpty()) {
            throw IOException(
                "No approved quotes for ${FilenameSanitizer.cleanBookTitle(book.title)} yet.",
            )
        }

        val variants = WallpaperLayoutVariant.entries
        val theme = if (isDark) WallpaperTheme.DARK else WallpaperTheme.LIGHT

        List(count) { index ->
            val variant = variants[nextVariantIndex % variants.size]
            val variantIndex = nextVariantIndex
            nextVariantIndex++
            val progress = getProgressText(book)
            val quote = usableQuotes[index % usableQuotes.size]
            val bitmap = TypographyWallpaperGenerator.generateWallpaperBitmap(
                context = appContext,
                quote = quote,
                bookTitle = document.title,
                progressText = progress,
                bookAuthor = document.author,
                layoutVariant = variant,
                isDarkMode = isDark,
            )
            TypographyWallpaper(
                id = "${book.id}-w$variantIndex",
                quote = quote,
                bookTitle = document.title,
                bookAuthor = document.author,
                progressText = progress,
                designIndex = variantIndex,
                layoutVariant = variant,
                theme = theme,
                bitmap = bitmap,
            )
        }
    }

    /** Selected books, falling back to the last-read or first library book. */
    private suspend fun resolveTargetBooks(): List<BookEntity> {
        val ids = _selectedBookIds.value
        if (ids.isNotEmpty()) {
            val found = ids.mapNotNull { database.bookDao().getById(it) }
            if (found.isNotEmpty()) return found
        }
        val current = _currentBook.value
            ?: database.bookDao().observeAll().first().firstOrNull()
        return listOfNotNull(current)
    }

    /** Approved, quality-vetted quotes for one book (wallpaper source). */
    private suspend fun approvedQuotesFor(bookId: String): List<String> =
        quoteRepository.getApprovedForBook(bookId)
            .map { it.selectedText }
            .filter(QuoteQualityValidator::isValidQuote)
            .distinct()

    private suspend fun loadBookDocument(book: BookEntity): EBookDocument {
        val fallbackTitle = FilenameSanitizer.cleanBookTitle(book.title)
            .ifBlank { "Untitled Book" }
        val format = EBookFormat.fromLabel(book.format)
            ?: EBookFormat.fromFileName(book.filePath)
        val file = book.filePath.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)
        if (format != null && file != null) {
            return try {
                documentLoader.loadDocument(
                    bookId = book.id,
                    file = file,
                    format = format,
                    fallbackTitle = fallbackTitle,
                    includeChapters = false,
                )
            } catch (e: Exception) {
                EBookDocument(
                    bookId = book.id,
                    format = format,
                    filePath = book.filePath,
                    metadata = com.example.vibereader.domain.model.EBookMetadata(
                        title = fallbackTitle,
                        author = book.author,
                    ),
                )
            }
        }
        return EBookDocument(
            bookId = book.id,
            format = format ?: EBookFormat.PDF,
            filePath = book.filePath,
            metadata = com.example.vibereader.domain.model.EBookMetadata(
                title = fallbackTitle,
                author = book.author,
            ),
        )
    }

    private fun newestContent(
        quote: QuoteEntity?,
        highlight: HighlightEntity?,
    ): SavedReadingText? {
        val savedQuote = quote?.let { SavedReadingText(it.bookId, it.selectedText, it.createdAt) }
        val savedHighlight = highlight?.let { SavedReadingText(it.bookId, it.selectedText, it.createdAt) }
        return listOfNotNull(savedQuote, savedHighlight).maxByOrNull(SavedReadingText::createdAt)
    }

    private suspend fun getProgressText(book: BookEntity): String {
        val progress = database.readingProgressDao().getForBook(book.id)?.progress
            ?.coerceIn(0f, 1f) ?: 0f
        val progressPercent = (progress * 100).toInt()
        val title = FilenameSanitizer.cleanBookTitle(book.title).ifBlank { "Untitled Book" }
        return "Vibe Reader • $progressPercent% through $title"
    }

    private data class SavedReadingText(
        val bookId: String,
        val text: String,
        val createdAt: Long,
    )

    class Factory(
        private val context: Context,
        private val database: VibeReaderDatabase,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(WallpaperStudioViewModel::class.java)) {
                return WallpaperStudioViewModel(context, database) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 8
        const val LOAD_MORE_COUNT = 2
        /** Hard cap on decoded full-screen bitmaps held by the carousel. */
        private const val MAX_CAROUSEL_WALLPAPERS = 12
        private const val RETRY_ROUNDS = 4
    }
}

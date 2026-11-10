package com.example.vibereader.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.repository.BookParserRepository
import com.example.vibereader.data.repository.BookRepository
import com.example.vibereader.data.repository.DeviceBookScanner
import com.example.vibereader.data.room.BookEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

data class LibraryScanState(
    val isScanning: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val hasFolderAccess: Boolean = false,
    val isProcessingMetadata: Boolean = false,
)

class LibraryViewModel(
    context: Context,
    private val database: VibeReaderDatabase,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val repository = BookRepository(
        bookDao = database.bookDao(),
        database = database,
        scanner = DeviceBookScanner(appContext),
        parserRepository = BookParserRepository(appContext, database),
    )
    private val _scanState = MutableStateFlow(
        LibraryScanState(hasFolderAccess = preferences.getString(TREE_URI_KEY, null) != null),
    )
    val scanState: StateFlow<LibraryScanState> = _scanState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Full catalog used for cover extraction. Kept separate from [books] so a
     * search term never prevents covers for other books from being prepared.
     */
    private val allBooks: StateFlow<List<BookEntity>> =
        database.bookDao().getAllBooksSortedByRecent().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * In-memory search + Last Activity ordering. The database flow is only
     * queried when the catalog changes; each keystroke filters this list
     * locally, so typing never triggers a Room query.
     */
    val books: StateFlow<List<BookEntity>> = combine(allBooks, _searchQuery) { catalog, query ->
        val term = query.trim()
        catalog.asSequence()
            .filter { book ->
                term.isEmpty() ||
                    book.title.contains(term, ignoreCase = true) ||
                    book.author?.contains(term, ignoreCase = true) == true
            }
            .sortedByDescending { book ->
                maxOf(book.lastReadAt, book.addedAt)
            }
            .toList()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val coverExtractionRequests = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            allBooks.collect { catalog ->
                catalog.forEach { book ->
                    val sourceFile = File(book.filePath).takeIf(File::isFile) ?: return@forEach
                    if (book.coverImagePath?.let(::File)?.isFile == true) return@forEach
                    val requestKey = "${book.id}:${sourceFile.absolutePath}"
                    if (coverExtractionRequests.add(requestKey)) {
                        launch(Dispatchers.IO) {
                            repository.ensureCoverImage(book)
                        }
                    }
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun saveFolderAccess(uri: Uri) {
        preferences.edit().putString(TREE_URI_KEY, uri.toString()).apply()
        _scanState.value = _scanState.value.copy(
            hasFolderAccess = true,
            errorMessage = null,
        )
        scanDeviceForBooks()
    }

    fun scanDeviceForBooks() {
        if (_scanState.value.isScanning || _scanState.value.isProcessingMetadata) return

        viewModelScope.launch(Dispatchers.IO) {
            _scanState.value = _scanState.value.copy(
                isScanning = true,
                statusMessage = "Scanning device for eBooks…",
                errorMessage = null,
            )
            try {
                val treeUri = preferences.getString(TREE_URI_KEY, null)?.let(Uri::parse)
                val summary = repository.scanDevice(treeUri)
                val pendingBooks = summary.pendingProcessing
                _scanState.value = _scanState.value.copy(
                    isScanning = false,
                    isProcessingMetadata = pendingBooks.isNotEmpty(),
                    statusMessage = if (pendingBooks.isNotEmpty()) {
                        "Indexed ${summary.added} new books. Preparing details for ${pendingBooks.size} books…"
                    } else {
                        scanCompleteMessage(summary.added, summary.removed)
                    },
                    errorMessage = summary.warnings.takeIf { it.isNotEmpty() }
                        ?.joinToString(separator = "\n"),
                )

                if (pendingBooks.isNotEmpty()) {
                    val processingWarnings = repository.processIndexedBooks(pendingBooks) { processed, total ->
                        _scanState.value = _scanState.value.copy(
                            isProcessingMetadata = processed < total,
                            statusMessage = if (processed < total) {
                                "Preparing details for ${pendingBooks.size} books… ($processed/$total)"
                            } else {
                                "Preparing details for ${pendingBooks.size} books…"
                            },
                        )
                    }
                    _scanState.value = _scanState.value.copy(
                        isProcessingMetadata = false,
                        statusMessage = scanCompleteMessage(summary.added, summary.removed),
                        errorMessage = combineMessages(
                            summary.warnings.takeIf { it.isNotEmpty() }
                                ?.joinToString(separator = "\n"),
                            processingWarnings.takeIf { it.isNotEmpty() }
                                ?.joinToString(separator = "\n"),
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _scanState.value = _scanState.value.copy(
                    isScanning = false,
                    isProcessingMetadata = false,
                    statusMessage = null,
                    errorMessage = error.localizedMessage ?: "The device scan failed.",
                )
            } finally {
                // Guarantee the library never stays stuck in a loading state.
                if (_scanState.value.isScanning || _scanState.value.isProcessingMetadata) {
                    _scanState.value = _scanState.value.copy(
                        isScanning = false,
                        isProcessingMetadata = false,
                        statusMessage = null,
                    )
                }
            }
        }
    }

    private fun combineMessages(vararg messages: String?): String? =
        messages
            .filterNot { it.isNullOrBlank() }
            .joinToString(separator = "\n")
            .takeIf { it.isNotBlank() }

    private fun scanCompleteMessage(added: Int, removed: Int): String = when {
        added > 0 || removed > 0 -> "Scan complete: $added added, $removed removed."
        else -> "Scan complete. Your library is up to date."
    }

    class Factory(
        private val context: Context,
        private val database: VibeReaderDatabase,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                throw IllegalArgumentException("Unsupported ViewModel type: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(context, database) as T
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "library_scan_preferences"
        const val TREE_URI_KEY = "selected_books_folder"
    }
}

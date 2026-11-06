package com.example.vibereader.data.repository

import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.room.BookDao
import com.example.vibereader.data.room.BookEntity
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID

data class BookScanSummary(
    val added: Int,
    val removed: Int,
    val warnings: List<String>,
    val pendingProcessing: List<IndexedBook>,
)

data class IndexedBook(
    val book: BookEntity,
    val sourceUri: Uri,
)

class BookRepository(
    private val bookDao: BookDao,
    private val database: VibeReaderDatabase,
    private val scanner: DeviceBookScanner,
    private val parserRepository: BookParserRepository,
) {
    fun observeBooks() = bookDao.observeAll()

    suspend fun getBook(bookId: String) = bookDao.getById(bookId)

    suspend fun ensureCoverImage(book: BookEntity) {
        parserRepository.ensureCoverImage(book)
    }

    suspend fun saveBook(book: BookEntity) = bookDao.upsert(book)

    suspend fun updateLastReadTimestamp(bookId: String, timestamp: Long) {
        bookDao.updateLastReadAt(bookId, timestamp)
    }

    suspend fun removeBook(book: BookEntity) {
        database.withTransaction {
            bookDao.delete(book)
        }
        File(book.filePath).takeIf { book.filePath.isNotBlank() }?.delete()
    }

    suspend fun scanDevice(folderUri: Uri? = null): BookScanSummary {
        val result = scanner.scan(folderUri)
        val candidateUris = result.books.mapTo(mutableSetOf()) { it.uri.toString() }
        val warnings = result.warnings.toMutableList()
        val pendingProcessing = mutableListOf<IndexedBook>()
        val indexedBooks = mutableListOf<BookEntity>()
        val existingByUri = bookDao.getAll().associateBy(BookEntity::documentUri)
        val existingBookIds = existingByUri.values.mapTo(mutableSetOf(), BookEntity::id)
        val bookIdsWithChapters = database.bookChapterDao().getBookIdsWithChapters().toSet()
        var added = 0

        result.books.forEach { scanned ->
            try {
                val existingBook = existingByUri[scanned.uri.toString()]
                val existingFile = existingBook?.filePath?.takeIf(String::isNotBlank)?.let(::File)
                val isCached = existingBook != null &&
                    existingBook.id in bookIdsWithChapters &&
                    existingBook.sourceModifiedAt == scanned.modifiedAt &&
                    existingFile?.isFile == true &&
                    (scanned.sizeBytes <= 0 || existingFile.length() == scanned.sizeBytes)
                val book = existingBook?.copy(
                    title = if (isCached) existingBook.title else scanned.title,
                    format = scanned.format,
                    filePath = if (isCached) existingBook.filePath
                        else scanned.filePath ?: existingBook.filePath,
                    fileSizeBytes = if (isCached) existingBook.fileSizeBytes
                        else scanned.sizeBytes.takeIf { it > 0 } ?: existingBook.fileSizeBytes,
                    sourceModifiedAt = scanned.modifiedAt,
                    isDeviceScanned = true,
                    scanSource = scanned.source,
                ) ?: BookEntity(
                    id = UUID.randomUUID().toString(),
                    title = scanned.title,
                    author = null,
                    documentUri = scanned.uri.toString(),
                    format = scanned.format,
                    filePath = scanned.filePath.orEmpty(),
                    fileSizeBytes = scanned.sizeBytes,
                    sourceModifiedAt = scanned.modifiedAt,
                    isDeviceScanned = true,
                    scanSource = scanned.source,
                )
                indexedBooks += book
                if (!isCached) {
                    pendingProcessing += IndexedBook(book, scanned.uri)
                }
                if (existingBook == null) added += 1
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                warnings += "Couldn't import ${scanned.title}: " +
                    (error.localizedMessage ?: "unsupported or unreadable file")
            }
        }
        if (indexedBooks.isNotEmpty()) {
            indexedBooks.forEach { book ->
                if (book.id !in existingBookIds) {
                    Log.d(TAG, "Inserting book: ${book.title} at ${book.filePath}")
                }
            }
            bookDao.upsert(indexedBooks)
        }

        var removed = 0
        if (result.completedSources.isNotEmpty()) {
            val staleBooks = bookDao.getDeviceScannedBooks().filter { book ->
                book.scanSource in result.completedSources &&
                    book.documentUri !in candidateUris
            }
            staleBooks.forEach { book ->
                val localCopy = File(book.filePath)
                database.withTransaction {
                    bookDao.deleteById(book.id)
                }
                if (book.filePath.isNotBlank() && localCopy.exists()) localCopy.delete()
                removed += 1
            }
        }
        return BookScanSummary(added, removed, warnings, pendingProcessing)
    }

    suspend fun processIndexedBook(indexedBook: IndexedBook) {
        parserRepository.processScannedBook(indexedBook.book, indexedBook.sourceUri)
    }

    private companion object {
        const val TAG = "VibeReaderScan"
    }
}

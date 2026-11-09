package com.example.vibereader.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.document.EBookDocumentLoader
import com.example.vibereader.data.room.BookChapterEntity
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.util.FilenameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID

class BookParserRepository(
    context: Context,
    private val database: VibeReaderDatabase,
) {
    private val appContext = context.applicationContext
    private val documentLoader = EBookDocumentLoader(appContext)

    suspend fun ensureCoverImage(book: BookEntity) {
        if (book.coverImagePath?.let(::File)?.isFile == true) return
        val sourceFile = File(book.filePath).takeIf(File::isFile) ?: return
        val format = EBookFormat.fromLabel(book.format) ?: return
        val coverPath = documentLoader.extractCover(sourceFile, format) ?: return
        database.bookDao().updateCoverImagePath(book.id, coverPath)
    }

    suspend fun importBook(
        uri: Uri,
        isDeviceScanned: Boolean = false,
        scanSource: String? = null,
        sourceModifiedAt: Long = 0,
    ): BookEntity = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        val mimeType = appContext.contentResolver.getType(uri)
        val format = EBookFormat.detect(displayName, mimeType)
            ?: throw IOException("Choose a PDF or EPUB file.")

        val bookId = UUID.randomUUID().toString()
        val booksDirectory = File(appContext.filesDir, BOOKS_DIRECTORY)
        if (!booksDirectory.exists() && !booksDirectory.mkdirs()) {
            throw IOException("Could not create the local books directory.")
        }
        val destination = File(booksDirectory, "$bookId.${format.extension}")
        val temporaryFile = File(booksDirectory, "$bookId.${format.extension}.tmp")

        try {
            copyFromUri(uri, temporaryFile)
            if (!temporaryFile.renameTo(destination)) {
                throw IOException("Could not finish saving the imported book.")
            }

            val document = withTimeoutOrNull(IMPORT_TIMEOUT_MS) {
                documentLoader.loadDocument(
                    bookId = bookId,
                    file = destination,
                    format = format,
                    fallbackTitle = displayName,
                )
            } ?: throw IOException("Timed out while reading this book.")

            val coverImagePath = withTimeoutOrNull(COVER_TIMEOUT_MS) {
                documentLoader.extractCover(destination, format)
            }
            val book = BookEntity(
                id = bookId,
                title = FilenameSanitizer.cleanBookTitle(
                    document.title.ifBlank { displayName },
                ).ifBlank { "Untitled Book" },
                author = document.author?.takeIf(String::isNotBlank),
                documentUri = uri.toString(),
                format = format.label,
                filePath = destination.absolutePath,
                fileSizeBytes = destination.length(),
                coverImagePath = coverImagePath,
                sourceModifiedAt = sourceModifiedAt,
                isDeviceScanned = isDeviceScanned,
                scanSource = scanSource,
            )
            val chapters = document.chapters.map { chapter ->
                BookChapterEntity(
                    bookId = bookId,
                    chapterIndex = chapter.chapterIndex,
                    title = chapter.title,
                    text = chapter.text,
                )
            }

            database.withTransaction {
                database.bookDao().upsert(book)
                database.bookChapterDao().insertAll(chapters)
            }
            book
        } catch (error: Exception) {
            temporaryFile.delete()
            destination.delete()
            throw error
        }
    }

    suspend fun processScannedBook(book: BookEntity, sourceUri: Uri): BookEntity {
        val format = EBookFormat.fromLabel(book.format)
            ?: throw IOException("Unsupported scanned book format: ${book.format}")
        val booksDirectory = File(appContext.filesDir, BOOKS_DIRECTORY)
        if (!booksDirectory.exists() && !booksDirectory.mkdirs()) {
            throw IOException("Could not create the local books directory.")
        }

        val destination = File(
            booksDirectory,
            "${book.id}-${book.sourceModifiedAt}.${format.extension}",
        )
        val previousFile = book.filePath.takeIf(String::isNotBlank)?.let(::File)
        val temporaryFile = File("${destination.absolutePath}.tmp")
        try {
            withContext(Dispatchers.IO) {
                copyFromUri(sourceUri, temporaryFile)
                if (!temporaryFile.renameTo(destination)) {
                    throw IOException("Could not finish saving the scanned book.")
                }
            }
            val document = withContext(Dispatchers.IO) {
                documentLoader.loadDocument(
                    bookId = book.id,
                    file = destination,
                    format = format,
                    fallbackTitle = book.title,
                )
            }
            // Covers are optional. A missing or slow thumbnail falls back to the
            // existing cover instead of failing the whole book.
            val coverImagePath = withTimeoutOrNull(COVER_TIMEOUT_MS) {
                documentLoader.extractCover(destination, format)
            }
            val processedBook = book.copy(
                title = FilenameSanitizer.cleanBookTitle(
                    document.title.ifBlank { book.title },
                ).ifBlank { "Untitled Book" },
                author = document.author?.takeIf(String::isNotBlank),
                filePath = destination.absolutePath,
                fileSizeBytes = destination.length(),
                coverImagePath = coverImagePath ?: book.coverImagePath,
            )
            val chapters = document.chapters.map { chapter ->
                BookChapterEntity(
                    bookId = book.id,
                    chapterIndex = chapter.chapterIndex,
                    title = chapter.title,
                    text = chapter.text,
                )
            }
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    database.bookDao().upsert(processedBook)
                    database.bookChapterDao().deleteForBook(book.id)
                    database.bookChapterDao().insertAll(chapters)
                }
            }
            if (
                previousFile != null &&
                previousFile.absolutePath != destination.absolutePath &&
                previousFile.parentFile?.absolutePath == booksDirectory.absolutePath
            ) {
                previousFile.delete()
            }
            return processedBook
        } catch (error: Exception) {
            temporaryFile.delete()
            throw error
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "book"
    }

    private fun copyFromUri(uri: Uri, destination: File) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected document.")
        input.use { source ->
            destination.outputStream().buffered().use { output ->
                source.copyTo(output)
            }
        }
        if (!destination.isFile || destination.length() == 0L) {
            throw IOException("The selected document is empty or could not be copied.")
        }
    }

    private companion object {
        const val BOOKS_DIRECTORY = "books"
        const val IMPORT_TIMEOUT_MS = 60_000L
        const val COVER_TIMEOUT_MS = 2_000L
    }
}

package com.example.vibereader.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.example.vibereader.core.database.VibeReaderDatabase
import com.example.vibereader.data.room.BookChapterEntity
import com.example.vibereader.data.room.BookEntity
import com.example.vibereader.util.CoverExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipFile

class BookParserRepository(
    context: Context,
    private val database: VibeReaderDatabase,
) {
    private val appContext = context.applicationContext

    suspend fun ensureCoverImage(book: BookEntity) {
        if (book.coverImagePath?.let(::File)?.isFile == true) return
        val sourceFile = File(book.filePath).takeIf(File::isFile) ?: return
        val format = when (book.format.uppercase()) {
            BookFormat.PDF.label -> BookFormat.PDF
            BookFormat.EPUB.label -> BookFormat.EPUB
            else -> return
        }
        val coverPath = withContext(Dispatchers.IO) {
            CoverExtractor.extractCover(appContext, sourceFile, format)
        } ?: return
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
        val format = when {
            displayName.endsWith(".pdf", ignoreCase = true) ||
                mimeType == "application/pdf" -> BookFormat.PDF
            displayName.endsWith(".epub", ignoreCase = true) ||
                mimeType == "application/epub+zip" -> BookFormat.EPUB
            else -> throw IOException("Choose a PDF or EPUB file.")
        }

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

            val parsedBook = when (format) {
                BookFormat.PDF -> parsePdf(destination, displayName)
                BookFormat.EPUB -> parseEpub(destination, displayName)
            }
            val book = BookEntity(
                id = bookId,
                title = parsedBook.title.ifBlank { displayName.withoutExtension() },
                author = parsedBook.author?.takeIf(String::isNotBlank),
                documentUri = uri.toString(),
                format = format.label,
                filePath = destination.absolutePath,
                fileSizeBytes = destination.length(),
                coverImagePath = CoverExtractor.extractCover(appContext, destination, format),
                sourceModifiedAt = sourceModifiedAt,
                isDeviceScanned = isDeviceScanned,
                scanSource = scanSource,
            )
            val chapters = parsedBook.chapters.mapIndexed { index, chapter ->
                BookChapterEntity(
                    bookId = bookId,
                    chapterIndex = index,
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
        val format = when (book.format.uppercase()) {
            BookFormat.PDF.label -> BookFormat.PDF
            BookFormat.EPUB.label -> BookFormat.EPUB
            else -> throw IOException("Unsupported scanned book format: ${book.format}")
        }
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
            val parsedBook = withContext(Dispatchers.Default) {
                when (format) {
                    BookFormat.PDF -> parsePdf(destination, book.title)
                    BookFormat.EPUB -> parseEpub(destination, book.title)
                }
            }
            val processedBook = book.copy(
                title = parsedBook.title.ifBlank { book.title },
                author = parsedBook.author?.takeIf(String::isNotBlank),
                filePath = destination.absolutePath,
                fileSizeBytes = destination.length(),
                coverImagePath = withContext(Dispatchers.IO) {
                    CoverExtractor.extractCover(appContext, destination, format)
                },
            )
            val chapters = parsedBook.chapters.mapIndexed { index, chapter ->
                BookChapterEntity(
                    bookId = book.id,
                    chapterIndex = index,
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

    private fun parsePdf(file: File, fallbackTitle: String): ParsedBook {
        PDFBoxResourceLoader.init(appContext)
        PDDocument.load(file).use { document ->
            val info = document.documentInformation
            val text = PDFTextStripper().getText(document).trim()
            return ParsedBook(
                title = info.title?.takeIf(String::isNotBlank) ?: fallbackTitle.withoutExtension(),
                author = info.author,
                chapters = listOf(ParsedChapter("Full text", text)),
            )
        }
    }

    private fun parseEpub(file: File, fallbackTitle: String): ParsedBook {
        ZipFile(file).use { epub ->
            val containerEntry = epub.getEntry(CONTAINER_PATH)
                ?: throw IOException("This EPUB has no package descriptor.")
            val container = parseXml(readEntry(epub, containerEntry))
            val rootFile = container.select("*").firstOrNull {
                it.normalizedTagName() == "rootfile"
            }
                ?: throw IOException("This EPUB has an invalid package descriptor.")
            val packagePath = rootFile.attr("full-path")
            val packageEntry = epub.getEntry(packagePath)
                ?: throw IOException("This EPUB package file is missing.")
            val packageDocument = parseXml(readEntry(epub, packageEntry))
            val metadata = packageDocument.select("*").firstOrNull {
                it.normalizedTagName() == "metadata"
            }
            val title = metadata?.firstDescendantText("title")
                ?: fallbackTitle.withoutExtension()
            val author = metadata?.firstDescendantText("creator")
            val manifestItems = packageDocument.select("*").filter {
                it.normalizedTagName() == "item"
            }
            val manifest = buildMap {
                manifestItems.forEach { item ->
                    put(item.attr("id"), item.attr("href"))
                }
            }
            val orderedPaths = mutableListOf<String>()
            val spineItems = packageDocument.select("*").filter {
                it.normalizedTagName() == "itemref"
            }
            for (item in spineItems) {
                manifest[item.attr("idref")]?.let { href ->
                    resolveArchivePath(packagePath, href)?.let(orderedPaths::add)
                }
            }

            val fallbackPaths = listZipEntryPaths(epub).filter(::isHtmlPath)
            val chapterPaths = (orderedPaths + fallbackPaths).distinct()
            if (chapterPaths.none { epub.getEntry(it) != null }) {
                throw IOException("No readable chapters were found in this EPUB.")
            }
            if (chapterPaths.size > MAX_CHAPTERS) {
                throw IOException("This EPUB contains too many chapters to import.")
            }

            var totalTextBytes = 0L
            val chapters = chapterPaths.mapNotNull { path ->
                val entry = epub.getEntry(path) ?: return@mapNotNull null
                if (!isHtmlPath(path)) return@mapNotNull null
                val chapterDocument = parseChapter(readEntry(epub, entry))
                val text = (chapterDocument.body()?.text().orEmpty())
                    .ifBlank { chapterDocument.text() }
                    .trim()
                if (text.isBlank()) return@mapNotNull null
                totalTextBytes += text.toByteArray(StandardCharsets.UTF_8).size
                if (totalTextBytes > MAX_TOTAL_TEXT_BYTES) {
                    throw IOException("The extracted EPUB text is too large to import.")
                }
                val chapterTitle = chapterDocument.selectFirst("h1, h2, title")
                    ?.text()
                    ?.takeIf(String::isNotBlank)
                    ?: "Chapter ${chaptersTitleIndex(chapterPaths, path) + 1}"
                ParsedChapter(chapterTitle, text)
            }
            if (chapters.isEmpty()) {
                throw IOException("No readable text was found in this EPUB.")
            }
            return ParsedBook(title, author, chapters)
        }
    }

    private fun parseChapter(bytes: ByteArray): Document {
        val document = Jsoup.parse(
            ByteArrayInputStream(bytes),
            StandardCharsets.UTF_8.name(),
            "",
            Parser.xmlParser(),
        )
        document.select("script, style, link[rel=stylesheet], nav").remove()
        document.select("*").forEach { element ->
            element.removeAttr("style")
            element.removeAttr("class")
        }
        return document
    }

    private fun parseXml(bytes: ByteArray): Document = Jsoup.parse(
        ByteArrayInputStream(bytes),
        StandardCharsets.UTF_8.name(),
        "",
        Parser.xmlParser(),
    )

    private fun readEntry(zip: ZipFile, entry: java.util.zip.ZipEntry): ByteArray {
        if (entry.size > MAX_ARCHIVE_ENTRY_BYTES) {
            throw IOException("An EPUB component is too large to import.")
        }
        val output = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_ARCHIVE_ENTRY_BYTES) {
                    throw IOException("An EPUB component is too large to import.")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun resolveArchivePath(packagePath: String, href: String): String? {
        val cleanHref = href.substringBefore('#').substringBefore('?')
        val resolved = java.net.URI(packagePath).resolve(cleanHref).normalize().path
            .removePrefix("/")
        if (resolved.isBlank() || resolved == ".." || resolved.startsWith("../")) return null
        return resolved
    }

    private fun org.jsoup.nodes.Element.firstDescendantText(localName: String): String? =
        select("*")
            .firstOrNull { it.normalizedTagName() == localName }
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun org.jsoup.nodes.Element.normalizedTagName(): String =
        tagName().substringAfter(':').lowercase()

    private fun isHtmlPath(path: String): Boolean =
        path.endsWith(".xhtml", true) || path.endsWith(".html", true) ||
            path.endsWith(".htm", true)

    private fun chaptersTitleIndex(paths: List<String>, currentPath: String): Int =
        paths.takeWhile { it != currentPath }.count(::isHtmlPath)

    private fun listZipEntryPaths(zip: ZipFile): List<String> {
        val paths = mutableListOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (!name.endsWith("/")) paths += name
            if (paths.size > MAX_ARCHIVE_ENTRIES) {
                throw IOException("This EPUB contains too many archive entries.")
            }
        }
        return paths
    }

    private fun String.withoutExtension(): String = substringBeforeLast('.', this)

    private data class ParsedBook(
        val title: String,
        val author: String?,
        val chapters: List<ParsedChapter>,
    )

    private data class ParsedChapter(val title: String, val text: String)

    private companion object {
        const val BOOKS_DIRECTORY = "books"
        const val CONTAINER_PATH = "META-INF/container.xml"
        const val MAX_ARCHIVE_ENTRY_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_TEXT_BYTES = 128L * 1024 * 1024
        const val MAX_CHAPTERS = 2_000
        const val MAX_ARCHIVE_ENTRIES = 20_000
    }
}

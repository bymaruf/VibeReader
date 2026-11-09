package com.example.vibereader.data.document

import android.content.Context
import com.example.vibereader.domain.model.EBookDocument
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.model.EBookMetadata
import com.example.vibereader.util.FilenameSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves the right [EBookParserEngine] for a file and exposes the resulting
 * document through the format-independent [EBookDocument] model.
 */
class EBookDocumentLoader(context: Context) {
    private val appContext = context.applicationContext
    private val engines: Map<EBookFormat, EBookParserEngine> = mapOf(
        EBookFormat.PDF to PdfDocumentEngine(appContext),
        EBookFormat.EPUB to EpubDocumentEngine(),
    )

    fun engineFor(format: EBookFormat): EBookParserEngine = engines.getValue(format)

    /**
     * Reads a document header.
     *
     * Pass `includeChapters = false` for a metadata-only pass (library indexing,
     * wallpaper curation). PDFs never return chapters here; use
     * [readChapterText] for lazy page extraction.
     */
    suspend fun loadDocument(
        bookId: String,
        file: File,
        format: EBookFormat,
        fallbackTitle: String,
        includeChapters: Boolean = true,
    ): EBookDocument = withContext(Dispatchers.IO) {
        val engine = engineFor(format)
        val rawMetadata = engine.readMetadata(file, fallbackTitle)
        // Every parser (PDF info dictionary, EPUB OPF, scanner fallback) can
        // expose a raw download filename. Sanitize once here so every consumer
        // — library, quote curator and wallpaper canvas — sees a clean title.
        val metadata = rawMetadata.copy(
            title = FilenameSanitizer.cleanBookTitle(rawMetadata.title)
                .ifBlank { FilenameSanitizer.cleanBookTitle(file.name) }
                .ifBlank { "Untitled Book" },
        )
        val chapters = if (includeChapters) engine.readChapters(file) else emptyList()
        EBookDocument(
            bookId = bookId,
            format = format,
            filePath = file.absolutePath,
            metadata = metadata.copy(
                coverImagePath = existingCoverFile(file)?.absolutePath
                    ?: metadata.coverImagePath,
            ),
            chapters = chapters,
        )
    }

    /** Metadata-only shortcut for callers that already have a [BookEntity]. */
    suspend fun loadMetadata(
        bookId: String,
        file: File,
        format: EBookFormat,
        fallbackTitle: String,
    ): EBookMetadata = loadDocument(
        bookId = bookId,
        file = file,
        format = format,
        fallbackTitle = fallbackTitle,
        includeChapters = false,
    ).metadata

    suspend fun readChapterText(
        file: File,
        format: EBookFormat,
        chapterIndex: Int,
    ): String = withContext(Dispatchers.IO) {
        engineFor(format).readChapterText(file, chapterIndex)
    }

    /**
     * Extracts and caches the cover art.
     *
     * Covers are optional, so every engine failure is reported as `null` and
     * the caller can keep the existing cover.
     */
    suspend fun extractCover(file: File, format: EBookFormat): String? =
        withContext(Dispatchers.IO) {
            existingCoverFile(file)?.let { return@withContext it.absolutePath }
            val destination = coverFile(file)
            val parent = destination.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) return@withContext null
            val extracted = engineFor(format).extractCover(file, destination)
            if (extracted && destination.isFile && destination.length() > 0) {
                destination.absolutePath
            } else {
                destination.delete()
                null
            }
        }

    private fun coverFile(file: File): File =
        File(File(appContext.cacheDir, COVERS_DIRECTORY), "${file.nameWithoutExtension}.png")

    private fun existingCoverFile(file: File): File? =
        coverFile(file).takeIf { it.isFile && it.length() > 0 }

    private companion object {
        const val COVERS_DIRECTORY = "covers"
    }
}

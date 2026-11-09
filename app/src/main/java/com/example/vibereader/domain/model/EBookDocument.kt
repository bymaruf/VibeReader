package com.example.vibereader.domain.model

import java.util.Locale

/**
 * Formats that VibeReader understands.
 *
 * The reader UI and the persistence layer only ever deal with this enum, so a
 * new format can be added by registering another parser engine.
 */
enum class EBookFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    PDF("PDF", "pdf", "application/pdf"),
    EPUB("EPUB", "epub", "application/epub+zip"),
    ;

    companion object {
        fun fromFileName(fileName: String?): EBookFormat? {
            val extension = fileName
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase(Locale.ROOT)
                ?: return null
            return entries.firstOrNull { it.extension == extension }
        }

        fun fromMimeType(mimeType: String?): EBookFormat? =
            entries.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }

        fun fromLabel(label: String?): EBookFormat? =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) }

        fun detect(fileName: String?, mimeType: String?): EBookFormat? =
            fromMimeType(mimeType) ?: fromFileName(fileName)
    }
}

/** Metadata read directly from an e-book header (EPUB OPF or PDF info dictionary). */
data class EBookMetadata(
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val identifier: String? = null,
    val coverImagePath: String? = null,
    val pageCount: Int? = null,
)

/** A readable unit of a document: an EPUB spine item or a PDF page. */
data class EBookChapter(
    val chapterIndex: Int,
    val title: String,
    val text: String,
)

/** Format-independent representation of an e-book file. */
data class EBookDocument(
    val bookId: String,
    val format: EBookFormat,
    val filePath: String,
    val metadata: EBookMetadata,
    val chapters: List<EBookChapter> = emptyList(),
) {
    val title: String
        get() = metadata.title

    val author: String?
        get() = metadata.author

    val hasExtractedText: Boolean
        get() = chapters.any { it.text.isNotBlank() }

    fun chapterAt(chapterIndex: Int): EBookChapter? =
        chapters.firstOrNull { it.chapterIndex == chapterIndex }
            ?: chapters.getOrNull(chapterIndex)
}

/**
 * Universal location for highlights and quotes.
 *
 * PDFs use the rendered page as [chapterIndex] and the character index within
 * the page text as [characterOffset]. EPUBs use the spine index and the offset
 * inside the chapter text. Neither the DAO nor the UI needs to know which one.
 */
data class EBookLocation(
    val bookId: String,
    val chapterIndex: Int,
    val characterOffset: Int,
    val endCharacterOffset: Int = characterOffset,
) {
    val isValid: Boolean
        get() = bookId.isNotBlank() &&
            chapterIndex >= 0 &&
            characterOffset >= 0 &&
            endCharacterOffset >= characterOffset
}

/** A selected excerpt together with its format-independent location. */
data class EBookQuote(
    val selectedText: String,
    val location: EBookLocation,
    val chapterTitle: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val bookId: String
        get() = location.bookId

    val chapterIndex: Int
        get() = location.chapterIndex

    val characterOffset: Int
        get() = location.characterOffset

    val endCharacterOffset: Int
        get() = location.endCharacterOffset
}

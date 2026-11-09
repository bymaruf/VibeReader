package com.example.vibereader.data.document

import com.example.vibereader.domain.model.EBookChapter
import com.example.vibereader.domain.model.EBookFormat
import com.example.vibereader.domain.model.EBookMetadata
import java.io.File

/**
 * A format-specific parser that turns a file on disk into the universal
 * [com.example.vibereader.domain.model.EBookDocument] model.
 *
 * Engines only do file I/O; callers are responsible for moving them onto a
 * background dispatcher and for applying timeouts.
 */
interface EBookParserEngine {
    val format: EBookFormat

    /**
     * Reads title/author/cover information from the document header.
     *
     * This is the only step that runs during library indexing, so it must be
     * cheap for formats whose text is rendered lazily (PDF).
     */
    fun readMetadata(file: File, fallbackTitle: String): EBookMetadata

    /**
     * Extracts every chapter/page of text.
     *
     * PDFs return an empty list because their text is read on demand through
     * [readChapterText]; EPUBs return their full spine.
     */
    fun readChapters(file: File): List<EBookChapter>

    /** Extracts one chapter (EPUB) or page (PDF) of text on demand. */
    fun readChapterText(file: File, chapterIndex: Int): String

    /** Writes the cover image to [destination]; returns true when one was found. */
    fun extractCover(file: File, destination: File): Boolean
}

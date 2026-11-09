package com.example.vibereader.util

import com.example.vibereader.data.room.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BookTitleTest {
    @Test
    fun cleansLegacyRowsImportedBeforeSanitization() {
        val book = BookEntity(
            id = "book-1",
            title = "Atomic habits \\( PDFDrive.com \\).pdf",
            author = null,
            documentUri = "content://books/1",
            format = "PDF",
        )

        assertEquals("Atomic habits", book.displayTitle())
    }

    @Test
    fun fallsBackWhenLegacyTitleHasNoMeaningfulText() {
        val book = BookEntity(
            id = "book-2",
            title = "(PDFDrive.com).pdf",
            author = null,
            documentUri = "content://books/2",
            format = "EPUB",
        )

        assertEquals("Untitled Book", book.displayTitle())
    }
}

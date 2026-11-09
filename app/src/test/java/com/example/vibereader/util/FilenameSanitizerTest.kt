package com.example.vibereader.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FilenameSanitizerTest {
    @Test
    fun stripsExtensionBracketsAndEscapedBackslashes() {
        assertEquals(
            "Atomic habits",
            FilenameSanitizer.cleanBookTitle(
                "Atomic habits \\( PDFDrive.com \\).pdf",
            ),
        )
    }

    @Test
    fun stripsDownloadTagsInAnyBracketStyle() {
        assertEquals(
            "Deep Work",
            FilenameSanitizer.cleanBookTitle("Deep Work (PDFDrive.com) [EPUB].epub"),
        )
        assertEquals(
            "The Alchemist",
            FilenameSanitizer.cleanBookTitle("www.pdfdrive.com - The Alchemist.pdf"),
        )
    }

    @Test
    fun keepsDotsThatBelongToTheTitle() {
        assertEquals(
            "Sapiens. A Brief History of Humankind",
            FilenameSanitizer.cleanBookTitle("Sapiens. A Brief History of Humankind.pdf"),
        )
    }

    @Test
    fun normalizesUnderscoresAndWhitespace() {
        assertEquals(
            "Atomic Habits James Clear",
            FilenameSanitizer.cleanBookTitle("  Atomic_Habits__James_Clear_ .pdf "),
        )
    }

    @Test
    fun returnsEmptyWhenNothingMeaningfulIsLeft() {
        assertEquals("", FilenameSanitizer.cleanBookTitle(""))
        assertEquals("", FilenameSanitizer.cleanBookTitle("(PDFDrive.com).pdf"))
    }
}

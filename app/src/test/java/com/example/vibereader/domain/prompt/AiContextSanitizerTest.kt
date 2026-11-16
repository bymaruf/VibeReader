package com.example.vibereader.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContextSanitizerTest {

    @Test
    fun removesHtmlPageNumbersAndArtifactLines() {
        val raw = """
            <p>First real line.</p>

            Page 12

            [Illustration]

            *** START OF THE PROJECT GUTENBERG EBOOK ***

            Second real line.
        """.trimIndent()

        val clean = AiContextSanitizer.sanitize(raw)

        assertEquals("First real line.\nSecond real line.", clean)
    }

    @Test
    fun collapsesWhitespaceAndControlCharacters() {
        val raw = "Alpha\t\tbeta\u0007\r\n\r\n\r\nGamma   delta"

        assertEquals("Alpha beta\nGamma delta", AiContextSanitizer.sanitize(raw))
    }

    @Test
    fun oneLineCollapsesParagraphs() {
        val raw = "First line.\n\nSecond   line."

        assertEquals("First line. Second line.", AiContextSanitizer.oneLine(raw))
    }

    @Test
    fun sanitizeSnippetPrefersSentenceBoundary() {
        val raw = "This is the first sentence. This is the second sentence that goes on for a while."

        val snippet = AiContextSanitizer.sanitizeSnippet(raw, 45)

        assertEquals("This is the first sentence.", snippet)
    }

    @Test
    fun truncateAtBoundaryNeverSplitsAWordWhenPossible() {
        val raw = "One two three four five six seven eight nine ten eleven twelve"

        val snippet = AiContextSanitizer.truncateAtBoundary(raw, 20)

        assertEquals("One two three four", snippet)
    }

    @Test
    fun punctuationOnlyLinesAreDiscarded() {
        val clean = AiContextSanitizer.sanitize("A real line.\n***\n—\nAnother line.")

        assertEquals("A real line.\nAnother line.", clean)
    }

    @Test
    fun blankInputStaysBlank() {
        assertEquals("", AiContextSanitizer.sanitize("   \n\n  "))
        assertFalse(AiContextSanitizer.sanitizeSnippet("   ", 100).isNotEmpty())
    }

    @Test
    fun shortTextIsReturnedUnchanged() {
        val raw = "A short clean line."

        assertTrue(AiContextSanitizer.sanitize(raw) == raw)
    }
}

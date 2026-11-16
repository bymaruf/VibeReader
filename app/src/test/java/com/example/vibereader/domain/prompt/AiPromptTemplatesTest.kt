package com.example.vibereader.domain.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptTemplatesTest {

    @Test
    fun metadataPromptCarriesNoBookText() {
        val prompt = AiPromptTemplates.buildBookMetadataSystemPrompt(
            bookTitle = "The Quiet Harbor",
            author = "Ada Writer",
        )

        assertTrue(prompt.contains("The Quiet Harbor"))
        assertTrue(prompt.contains("Ada Writer"))
        assertTrue(prompt.contains(AiPromptTemplates.UNKNOWN_BOOK))
        assertFalse(prompt.contains("Passages:"))
        assertTrue(prompt.length < 600)
    }

    @Test
    fun metadataPromptOmitsMissingAuthor() {
        val prompt = AiPromptTemplates.buildBookMetadataSystemPrompt(
            bookTitle = "Anonymous Work",
            author = null,
        )

        assertFalse(prompt.contains(" by "))
        assertTrue(prompt.contains("Anonymous Work"))
    }

    @Test
    fun ragPromptIncludesSanitizedPassagesOnly() {
        val prompt = AiPromptTemplates.buildBookRagSystemPrompt(
            bookTitle = "The Quiet Harbor",
            author = "Ada Writer",
            passages = "<p>The harbor slept.</p>\n\nPage 9\n\nThe boats returned.",
        )

        assertTrue(prompt.contains("The harbor slept."))
        assertTrue(prompt.contains("The boats returned."))
        assertFalse(prompt.contains("<p>"))
        assertFalse(prompt.contains("Page 9"))
        assertTrue(prompt.contains("only the passages below"))
    }

    @Test
    fun unknownBookFlagIsDetectedOnlyWhenItIsTheAnswer() {
        listOf(
            "UNKNOWN_BOOK",
            "unknown_book",
            "`UNKNOWN_BOOK`",
            "\"UNKNOWN_BOOK\"",
            "UNKNOWN_BOOK\n",
        ).forEach { answer ->
            assertTrue(answer, AiPromptTemplates.isUnknownBookResponse(answer))
        }

        listOf(
            "The answer is UNKNOWN_BOOK because the model lacks data.",
            "I do not know this book.",
            "",
        ).forEach { answer ->
            assertFalse(answer, AiPromptTemplates.isUnknownBookResponse(answer))
        }
    }

    @Test
    fun quoteMetadataRequestAsksForUnknownBookInsteadOfInventing() {
        val request = AiPromptTemplates.buildQuoteMetadataRequest(
            bookTitle = "The Quiet Harbor",
            author = "Ada Writer",
            count = 10,
            excludedQuotes = listOf("The harbor slept."),
        )

        assertTrue(request.contains(AiPromptTemplates.UNKNOWN_BOOK))
        assertTrue(request.contains("Do not repeat these already collected quotes"))
        assertTrue(request.contains("The harbor slept."))
        assertTrue(request.length < 700)
    }

    @Test
    fun quoteRagRequestClampsExclusionLines() {
        val longQuote = "x".repeat(200)

        val request = AiPromptTemplates.buildQuoteRagRequest(
            bookTitle = "The Quiet Harbor",
            author = null,
            passages = "The boats returned at dawn.",
            count = 5,
            excludedQuotes = listOf(longQuote),
        )

        val exclusionLine = request.lineSequence()
            .first { it.startsWith("- ") }
        assertTrue(exclusionLine.length <= 72)
    }

    @Test
    fun summaryPromptSanitizesInjectedText() {
        val prompt = AiPromptTemplates.buildHumanSummaryPrompt(
            "<p>First.</p>\n\nPage 3\n\nSecond.",
        )

        assertTrue(prompt.contains("First."))
        assertTrue(prompt.contains("Second."))
        assertFalse(prompt.contains("<p>"))
        assertFalse(prompt.contains("Page 3"))
    }
}

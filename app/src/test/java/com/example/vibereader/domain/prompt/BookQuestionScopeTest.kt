package com.example.vibereader.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookQuestionScopeTest {

    @Test
    fun explicitPassageReferencesRequireLocalContext() {
        listOf(
            "Explain this passage.",
            "What does this mean?",
            "Summarize the current chapter",
            "What happens here?",
            "In this context, why did she leave?",
            "What is the highlighted text saying?",
            "Explain that",
        ).forEach { question ->
            assertTrue(question, BookQuestionScope.requiresPassageContext(question))
        }
    }

    @Test
    fun generalBookQuestionsCanUseMetadataFirst() {
        listOf(
            "Who wrote this book?",
            "What is the theme of this book?",
            "How many chapters does it have?",
            "What year was it published?",
            "Give me an overview of the plot.",
            "",
        ).forEach { question ->
            assertFalse(question, BookQuestionScope.requiresPassageContext(question))
        }
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(BookQuestionScope.requiresPassageContext("EXPLAIN THIS PARAGRAPH"))
    }

    @Test
    fun shortPronounPromptsCountAsPassageQuestions() {
        assertEquals(true, BookQuestionScope.requiresPassageContext("why"))
        assertFalse(BookQuestionScope.requiresPassageContext("why did the author write it in 1920"))
    }
}

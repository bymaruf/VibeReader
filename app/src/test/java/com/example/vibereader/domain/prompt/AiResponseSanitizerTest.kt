package com.example.vibereader.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResponseSanitizerTest {

    @Test
    fun `removes closed and unclosed thinking blocks`() {
        val raw = """
            <think>internal plan that must never show</think>
            <thought>another hidden trace</thought>
            The visible answer is here.
            <think>unclosed tail
        """.trimIndent()

        assertEquals("The visible answer is here.", AiResponseSanitizer.sanitize(raw))
    }

    @Test
    fun `removes bracket thought tags and markdown fences`() {
        val raw = """
            [THOUGHT]hidden reasoning[/THOUGHT]
            ```json
            ["A clean quote inside a fence."]
            ```
        """.trimIndent()

        assertEquals(
            """["A clean quote inside a fence."]""",
            AiResponseSanitizer.sanitize(raw, extractFencedPayload = true),
        )
    }

    @Test
    fun `removes inline conversational intro and sign-off`() {
        val raw = "Sure! Here are the key insights: Patience matters. Hope this helps!"
        assertEquals("Patience matters.", AiResponseSanitizer.sanitize(raw))
    }

    @Test
    fun `display cleanup removes markdown but keeps prose`() {
        val raw = """
            Here are the key insights:
            ## Main idea
            **Patience** matters when *life* changes.
            - Pay attention to small moments.
            [Read more](https://example.com) if you want.
        """.trimIndent()

        assertEquals(
            "Main idea\nPatience matters when life changes.\n• Pay attention to small moments.\nRead more if you want.",
            AiResponseSanitizer.sanitizeForDisplay(raw),
        )
    }

    @Test
    fun `accepts natural sentences that begin with object`() {
        assertFalse(
            AiResponseSanitizer.isArtifact(
                "Object quote one is complete and faithful to the book.",
            ),
        )
        assertFalse(
            AiResponseSanitizer.isArtifact(
                "Class struggle shaped the world these characters inherited.",
            ),
        )
    }

    @Test
    fun `rejects code xml and reasoning artifacts`() {
        assertTrue(
            AiResponseSanitizer.isArtifact(
                "fun loadQuotes(bookId: String): List<Quote>",
            ),
        )
        assertTrue(
            AiResponseSanitizer.isArtifact("<response><quote>Hello</quote></response>"),
        )
        assertTrue(
            AiResponseSanitizer.isArtifact("Here are the quotes you requested."),
        )
    }
}

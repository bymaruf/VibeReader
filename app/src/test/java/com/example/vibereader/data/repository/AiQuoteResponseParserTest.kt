package com.example.vibereader.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiQuoteResponseParserTest {

    @Test
    fun `parses a plain JSON string array`() {
        val result = AiQuoteResponseParser.parse(
            """
            [
              "The first complete quote goes here.",
              "A second meaningful passage follows."
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "The first complete quote goes here.",
                "A second meaningful passage follows.",
            ),
            result,
        )
    }

    @Test
    fun `parses a fenced JSON array`() {
        val result = AiQuoteResponseParser.parse(
            """
            ```json
            [
              "Fenced quote number one is complete.",
              "Fenced quote number two is complete."
            ]
            ```
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "Fenced quote number one is complete.",
                "Fenced quote number two is complete.",
            ),
            result,
        )
    }

    @Test
    fun `parses an object wrapper with a quotes array`() {
        val result = AiQuoteResponseParser.parse(
            """
            {
              "quotes": [
                "Wrapped quote one is complete.",
                "Wrapped quote two is complete."
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "Wrapped quote one is complete.",
                "Wrapped quote two is complete.",
            ),
            result,
        )
    }

    @Test
    fun `parses an array of objects using known text fields`() {
        val result = AiQuoteResponseParser.parse(
            """
            [
              { "quote": "Object quote one is complete." },
              { "text": "Object quote two is complete." },
              { "content": "Object quote three is complete." }
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "Object quote one is complete.",
                "Object quote two is complete.",
                "Object quote three is complete.",
            ),
            result,
        )
    }

    @Test
    fun `parses quotes nested inside a data object`() {
        val result = AiQuoteResponseParser.parse(
            """{"data": {"quotes": ["Nested quote one is complete."]}}""",
        )

        assertEquals(listOf("Nested quote one is complete."), result)
    }

    @Test
    fun `tolerates prose before and after the JSON payload`() {
        val result = AiQuoteResponseParser.parse(
            """
            Here are the quotes you asked for:
            ["Prose wrapped quote is complete and faithful."]
            I hope these help!
            """.trimIndent(),
        )

        assertEquals(listOf("Prose wrapped quote is complete and faithful."), result)
    }

    @Test
    fun `unescapes quotes and unicode inside JSON strings`() {
        val result = AiQuoteResponseParser.parse(
            """["She said \"hello\" and left the room quietly."]""",
        )

        assertEquals(
            listOf("""She said "hello" and left the room quietly."""),
            result,
        )
    }

    @Test
    fun `trims values and skips blank array entries`() {
        val result = AiQuoteResponseParser.parse(
            """["  A padded quote with spaces is complete.  ", "", "   "]""",
        )

        assertEquals(listOf("A padded quote with spaces is complete."), result)
    }

    @Test
    fun `falls back to a numbered plain-text list`() {
        val result = AiQuoteResponseParser.parse(
            """
            1. The numbered quote is complete and meaningful.
            2) Another numbered quote is complete and meaningful.
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "The numbered quote is complete and meaningful.",
                "Another numbered quote is complete and meaningful.",
            ),
            result,
        )
    }

    @Test
    fun `returns an empty list for blank input`() {
        assertTrue(AiQuoteResponseParser.parse("   \n\t  ").isEmpty())
    }
}

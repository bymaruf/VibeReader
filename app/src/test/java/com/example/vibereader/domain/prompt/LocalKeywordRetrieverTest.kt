package com.example.vibereader.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalKeywordRetrieverTest {

    @Test
    fun returnsTheParagraphThatMatchesTheQuestion() {
        val photosynthesis =
            "Photosynthesis converts sunlight into chemical energy inside the chloroplast. " +
                "The plant stores that energy in sugar molecules, which later fuel growth " +
                "and reproduction throughout the changing seasons of the year."
        val castle =
            "The castle stood above the valley for three hundred years. Its stone walls " +
                "had weathered every storm, and its empty halls still carried the echo of " +
                "footsteps from a family that had long since disappeared from history."

        val snippets = LocalKeywordRetriever.selectRelevantSnippets(
            query = "How does photosynthesis store energy?",
            sources = listOf(castle, photosynthesis),
            maxSnippets = 1,
        )

        assertEquals(photosynthesis, snippets.single())
    }

    @Test
    fun capsTotalSelectedCharacters() {
        val longParagraph = buildString {
            repeat(14) { index ->
                append("Sentence $index about energy and light moving through the room. ")
            }
        }.trim()

        val snippets = LocalKeywordRetriever.selectRelevantSnippets(
            query = "energy light",
            sources = listOf(longParagraph),
            maxSnippets = 4,
            maxChars = 400,
        )

        assertTrue(snippets.isNotEmpty())
        assertTrue(snippets.sumOf(String::length) <= 400)
    }

    @Test
    fun capsTheNumberOfSelectedSnippets() {
        val sources = (1..5).map { index ->
            "Paragraph $index discusses lanterns and shadows in careful detail. " +
                "It describes how a lantern throws light across the floor while the " +
                "surrounding room remains quiet, still, and largely unseen by everyone."
        }

        val snippets = LocalKeywordRetriever.selectRelevantSnippets(
            query = "lantern light",
            sources = sources,
            maxSnippets = 2,
        )

        assertEquals(2, snippets.size)
    }

    @Test
    fun priorityContextWinsForPronounOnlyQuestions() {
        val current = "The lantern flickered twice and then went dark."
        val other =
            "A long paragraph about an unrelated journey across the northern sea, with " +
                "sailors, maps, and a captain who refused to turn back even when the " +
                "weather worsened and the crew began to doubt the plan entirely."

        val snippets = LocalKeywordRetriever.selectRelevantSnippets(
            query = "what does this mean",
            sources = listOf(other),
            prioritySources = listOf(current),
            maxSnippets = 1,
        )

        assertEquals(current, snippets.single())
    }

    @Test
    fun excludesPassagesContainingAlreadyCollectedQuotes() {
        val first =
            "The lantern flickered twice before the room went dark and the child slept. " +
                "Outside, the rain continued against the glass while the house settled."
        val second =
            "Morning arrived with a pale sky over the harbor and the boats returned. " +
                "The town slowly woke, and the smell of bread drifted through the streets."
        val excluded = "the lantern flickered twice"

        val snippets = LocalKeywordRetriever.selectRelevantSnippets(
            query = "lantern harbor morning",
            sources = listOf(first, second),
            maxSnippets = 2,
            excludePhrases = listOf(excluded),
        )

        assertFalse(snippets.any { it.contains("lantern", ignoreCase = true) })
        assertEquals(second, snippets.single())
    }

    @Test
    fun quoteModePrefersCompleteReflectiveParagraphs() {
        val choppy = "A short broken line."
        val reflective =
            "A room can hold the silence of everyone who has ever left it, and still " +
                "the morning light arrives without apology, finding the chairs exactly " +
                "where they were placed and the table waiting for a meal that never comes."

        val snippets = LocalKeywordRetriever.selectRelevantSnippets(
            query = "what does this mean",
            sources = listOf(choppy, reflective),
            maxSnippets = 1,
            preferQuotes = true,
        )

        assertEquals(reflective, snippets.single())
    }

    @Test
    fun emptySourcesReturnNothing() {
        assertTrue(
            LocalKeywordRetriever.selectRelevantSnippets(
                query = "anything",
                sources = emptyList(),
            ).isEmpty(),
        )
    }
}

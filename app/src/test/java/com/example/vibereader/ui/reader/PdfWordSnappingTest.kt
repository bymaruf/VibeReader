package com.example.vibereader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PdfWordSnappingTest {

    @Test
    fun `builder groups glyphs into words and lines`() {
        val glyphs = listOf(
            glyph("H", 0.10f, 0.10f, 0.12f, 0.14f, 0),
            glyph("i", 0.12f, 0.10f, 0.14f, 0.14f, 1),
            glyph("t", 0.15f, 0.10f, 0.17f, 0.14f, 3),
            glyph("h", 0.17f, 0.10f, 0.19f, 0.14f, 4),
            glyph("e", 0.19f, 0.10f, 0.21f, 0.14f, 5),
            glyph("r", 0.10f, 0.20f, 0.12f, 0.24f, 7),
            glyph("e", 0.12f, 0.20f, 0.14f, 0.24f, 8),
        )

        val words = PdfWordBuilder.build(glyphs)

        assertEquals(2, words.size)
        assertEquals("Hithe", words[0].text)
        assertEquals(0, words[0].lineIndex)
        assertEquals("re", words[1].text)
        assertEquals(1, words[1].lineIndex)
        assertEquals("Hithe re", words[0].text + " " + words[1].text)
    }

    @Test
    fun `snap chooses the nearest word on the nearest line`() {
        val words = listOf(
            word("alpha", 0.10f, 0.10f, 0.20f, 0.14f, 0, 0, 5),
            word("beta", 0.30f, 0.10f, 0.38f, 0.14f, 0, 6, 10),
            word("gamma", 0.10f, 0.20f, 0.22f, 0.24f, 1, 11, 16),
        )

        val snapped = PdfWordSnapper.snap(words, xFraction = 0.33f, yFraction = 0.12f)

        assertNotNull(snapped)
        assertEquals("beta", snapped!!.text)
        assertEquals(0, snapped.lineIndex)
    }

    @Test
    fun `previous line wins while the touch stays inside the hysteresis band`() {
        val firstLineWord = word("first", 0.10f, 0.10f, 0.20f, 0.15f, 0, 0, 5)
        val secondLineWord = word("second", 0.10f, 0.17f, 0.22f, 0.22f, 1, 6, 12)
        val words = listOf(firstLineWord, secondLineWord)

        // 0.165 sits between the lines and is geometrically closer to line 2,
        // but it is within 0.35 * line height of line 1.
        val snapped = PdfWordSnapper.snap(
            words = words,
            xFraction = 0.15f,
            yFraction = 0.165f,
            previousWord = firstLineWord,
        )

        assertEquals("first", snapped!!.text)
        assertEquals(0, snapped.lineIndex)
    }

    @Test
    fun `snap moves to the next paragraph once the finger leaves the band`() {
        val firstLineWord = word("first", 0.10f, 0.10f, 0.20f, 0.15f, 0, 0, 5)
        val secondLineWord = word("second", 0.10f, 0.40f, 0.22f, 0.45f, 1, 6, 12)
        val words = listOf(firstLineWord, secondLineWord)

        val snapped = PdfWordSnapper.snap(
            words = words,
            xFraction = 0.15f,
            yFraction = 0.42f,
            previousWord = firstLineWord,
        )

        assertEquals("second", snapped!!.text)
        assertEquals(1, snapped.lineIndex)
    }

    @Test
    fun `horizontal distance prefers a word that contains the touch`() {
        val words = listOf(
            word("left", 0.10f, 0.10f, 0.20f, 0.14f, 0, 0, 4),
            word("right", 0.24f, 0.10f, 0.34f, 0.14f, 0, 5, 10),
        )

        val snapped = PdfWordSnapper.snap(words, xFraction = 0.31f, yFraction = 0.12f)

        assertEquals("right", snapped!!.text)
    }

    @Test
    fun `snap returns null for an empty page`() {
        assertEquals(null, PdfWordSnapper.snap(emptyList(), 0.5f, 0.5f))
    }

    private fun glyph(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        charStart: Int,
    ) = PdfGlyph(
        text = text,
        rect = PdfSelectionRect(left, top, right, bottom),
        charStart = charStart,
        charEnd = charStart + text.length,
    )

    private fun word(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        lineIndex: Int,
        charStart: Int,
        charEnd: Int,
    ) = PdfWordBounds(
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        charStart = charStart,
        charEnd = charEnd,
        lineIndex = lineIndex,
    )
}

package com.example.vibereader.ui.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A single glyph extracted from a PDF page, in normalized page coordinates. */
data class PdfGlyph(
    val text: String,
    val rect: PdfSelectionRect,
    val charStart: Int,
    val charEnd: Int,
)

/**
 * A word-like run of glyphs sharing one text line. [charStart]/[charEnd] point
 * into [PdfPageLayout.text], so a snapped selection can be stored as a range.
 */
data class PdfWordBounds(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val charStart: Int,
    val charEnd: Int,
    val lineIndex: Int,
) {
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top

    fun contains(xFraction: Float, yFraction: Float): Boolean =
        xFraction >= left && xFraction <= right &&
            yFraction >= top && yFraction <= bottom
}

/** Everything needed to select text on one PDF page without reopening it. */
data class PdfPageLayout(
    val pageWidth: Float,
    val pageHeight: Float,
    val text: String,
    val charRects: List<PdfSelectionRect?>,
    val words: List<PdfWordBounds>,
)

/** Groups glyphs into lines and word-sized bounding boxes. */
object PdfWordBuilder {
    fun build(glyphs: List<PdfGlyph>): List<PdfWordBounds> {
        val valid = glyphs.filter { it.rect.isValid && it.text.isNotEmpty() }
        if (valid.isEmpty()) return emptyList()

        val sortedByVerticalPosition = valid.sortedWith(
            compareBy({ (it.rect.top + it.rect.bottom) / 2f }, { it.rect.left }),
        )
        val lines = mutableListOf<MutableList<PdfGlyph>>()

        sortedByVerticalPosition.forEach { glyph ->
            val glyphHeight = (glyph.rect.bottom - glyph.rect.top).coerceAtLeast(0.0001f)
            val centerY = (glyph.rect.top + glyph.rect.bottom) / 2f
            val line = lines.firstOrNull { lineGlyphs ->
                val lineTop = lineGlyphs.minOf { it.rect.top }
                val lineBottom = lineGlyphs.maxOf { it.rect.bottom }
                val lineHeight = (lineBottom - lineTop).coerceAtLeast(0.0001f)
                val overlap = min(lineBottom, glyph.rect.bottom) - max(lineTop, glyph.rect.top)
                val centerInside =
                    centerY >= lineTop - glyphHeight * LINE_CENTER_TOLERANCE &&
                        centerY <= lineBottom + glyphHeight * LINE_CENTER_TOLERANCE
                centerInside ||
                    overlap >= min(lineHeight, glyphHeight) * LINE_OVERLAP_THRESHOLD
            }
            if (line != null) {
                line.add(glyph)
            } else {
                lines.add(mutableListOf(glyph))
            }
        }

        val result = mutableListOf<PdfWordBounds>()
        lines
            .sortedBy { line -> line.minOf { it.rect.top } }
            .forEachIndexed { lineIndex, lineGlyphs ->
                val lineTop = lineGlyphs.minOf { it.rect.top }
                val lineBottom = lineGlyphs.maxOf { it.rect.bottom }
                val lineHeight = (lineBottom - lineTop).coerceAtLeast(0.0001f)
                val wordGap = lineHeight * WORD_GAP_RATIO
                val sorted = lineGlyphs.sortedBy { it.rect.left }

                var currentWord = mutableListOf<PdfGlyph>()
                var previousRight: Float? = null

                fun flushWord() {
                    if (currentWord.isEmpty()) return
                    val word = currentWord
                    result += PdfWordBounds(
                        text = word.joinToString(separator = "") { it.text },
                        left = word.minOf { it.rect.left },
                        top = word.minOf { it.rect.top },
                        right = word.maxOf { it.rect.right },
                        bottom = word.maxOf { it.rect.bottom },
                        charStart = word.minOf { it.charStart },
                        charEnd = word.maxOf { it.charEnd },
                        lineIndex = lineIndex,
                    )
                    currentWord = mutableListOf()
                }

                sorted.forEach { glyph ->
                    if (glyph.text.isBlank()) {
                        flushWord()
                        previousRight = glyph.rect.right
                        return@forEach
                    }
                    val gap = previousRight?.let { glyph.rect.left - it } ?: 0f
                    if (currentWord.isNotEmpty() && gap > wordGap) {
                        flushWord()
                    }
                    currentWord.add(glyph)
                    previousRight = glyph.rect.right
                }
                flushWord()
            }
        return result
    }
}

/**
 * Snaps a touch to a word on the nearest text line. [previousWord] adds
 * hysteresis: while the finger stays within a small band around the current
 * line, that line wins, which prevents accidental jumps to adjacent
 * paragraphs.
 */
object PdfWordSnapper {
    fun snap(
        words: List<PdfWordBounds>,
        xFraction: Float,
        yFraction: Float,
        previousWord: PdfWordBounds? = null,
    ): PdfWordBounds? {
        if (words.isEmpty()) return null

        val lines = words
            .groupBy(PdfWordBounds::lineIndex)
            .toSortedMap()
            .map { (lineIndex, lineWords) ->
                PdfWordLine(
                    lineIndex = lineIndex,
                    top = lineWords.minOf { it.top },
                    bottom = lineWords.maxOf { it.bottom },
                    words = lineWords.sortedBy { it.left },
                )
            }
        if (lines.isEmpty()) return null

        val nearestLine = lines.minWith(
            compareBy(
                { verticalDistanceToLine(it.top, it.bottom, yFraction) },
                { abs((it.top + it.bottom) / 2f - yFraction) },
            ),
        )
        val previousLine = previousWord?.let { word ->
            lines.firstOrNull { it.lineIndex == word.lineIndex }
        }
        val targetLine = if (
            previousLine != null &&
            previousLine.lineIndex != nearestLine.lineIndex &&
            verticalDistanceToLine(previousLine.top, previousLine.bottom, yFraction) <=
            previousLine.height * LINE_HYSTERESIS_RATIO
        ) {
            previousLine
        } else {
            nearestLine
        }

        return targetLine.words.minWith(
            compareBy(
                { horizontalDistanceToWord(it.left, it.right, xFraction) },
                { abs((it.left + it.right) / 2f - xFraction) },
            ),
        )
    }

    private data class PdfWordLine(
        val lineIndex: Int,
        val top: Float,
        val bottom: Float,
        val words: List<PdfWordBounds>,
    ) {
        val height: Float get() = (bottom - top).coerceAtLeast(0.0001f)
    }
}

private const val LINE_CENTER_TOLERANCE = 0.35f
private const val LINE_OVERLAP_THRESHOLD = 0.5f
private const val WORD_GAP_RATIO = 0.32f
private const val LINE_HYSTERESIS_RATIO = 0.35f

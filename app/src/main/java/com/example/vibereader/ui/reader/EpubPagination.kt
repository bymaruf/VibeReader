package com.example.vibereader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vibereader.data.room.BookChapterEntity
import kotlin.math.ceil
import kotlin.math.max

data class EpubPage(
    val globalIndex: Int,
    val chapterIndex: Int,
    val chapterTitle: String,
    val text: String,
    /** Start offset of [text] within the full chapter text. */
    val chapterStartOffset: Int,
    val isChapterStart: Boolean,
)

/**
 * Computes viewport-sized EPUB pages for horizontal reading.
 *
 * Pagination is measured against the actual text layout using [TextMeasurer] so
 * page boundaries respect font metrics, line height, margins, and the space taken
 * by the chapter title on the first page of a chapter.
 */
@Composable
fun rememberEpubPages(
    chapters: List<BookChapterEntity>,
    viewportWidthDp: Dp,
    viewportHeightDp: Dp,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    marginDp: Float,
): List<EpubPage> {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val widthPx = with(density) { (viewportWidthDp - (marginDp.dp * 2)).coerceAtLeast(1.dp).roundToPx() }
    val heightPx = with(density) { (viewportHeightDp - 48.dp).coerceAtLeast(1.dp).roundToPx() } // 24dp top+bottom padding

    return remember(chapters, widthPx, heightPx, fontSize, lineHeight) {
        paginateEpubChapters(
            chapters = chapters,
            textMeasurer = textMeasurer,
            maxWidthPx = widthPx,
            maxHeightPx = heightPx,
            bodyStyle = TextStyle(fontSize = fontSize, lineHeight = lineHeight),
        )
    }
}

private fun paginateEpubChapters(
    chapters: List<BookChapterEntity>,
    textMeasurer: TextMeasurer,
    maxWidthPx: Int,
    maxHeightPx: Int,
    bodyStyle: TextStyle,
): List<EpubPage> {
    if (chapters.isEmpty()) return emptyList()
    val pages = mutableListOf<EpubPage>()
    val titleStyle = TextStyle(fontSize = (bodyStyle.fontSize.value * 1.35f).sp, lineHeight = (bodyStyle.lineHeight.value * 1.2f).sp)
    val titleSpacingPx = (bodyStyle.fontSize.value * 0.8f).toInt().coerceAtLeast(8)

    for ((chapterIndex, chapter) in chapters.withIndex()) {
        val fullText = chapter.text
        val titleHeight = if (chapter.title.isNotBlank()) {
            textMeasurer.measure(
                text = AnnotatedString(chapter.title),
                style = titleStyle,
                constraints = Constraints(maxWidth = maxWidthPx),
                overflow = TextOverflow.Clip,
                maxLines = 4,
            ).size.height + titleSpacingPx
        } else 0

        if (fullText.isBlank()) {
            pages += EpubPage(
                globalIndex = pages.size,
                chapterIndex = chapterIndex,
                chapterTitle = chapter.title,
                text = "",
                chapterStartOffset = 0,
                isChapterStart = true,
            )
            continue
        }

        var start = 0
        var firstPageOfChapter = true
        while (start < fullText.length) {
            val availableHeight = (maxHeightPx - if (firstPageOfChapter) titleHeight else 0)
                .coerceAtLeast((bodyStyle.lineHeight.value).toInt().coerceAtLeast(1))
            val remaining = fullText.substring(start)
            val result = textMeasurer.measure(
                text = AnnotatedString(remaining),
                style = bodyStyle,
                constraints = Constraints(maxWidth = maxWidthPx, maxHeight = availableHeight),
                overflow = TextOverflow.Clip,
            )
            val endExclusive = if (!result.didOverflowHeight || result.lineCount == 0) {
                remaining.length
            } else {
                // Last fully/partially visible line end in the laid out (possibly clipped) text.
                val visibleLastLine = (result.lineCount - 1).coerceAtLeast(0)
                result.getLineEnd(visibleLastLine, visibleEnd = true).coerceAtLeast(1)
            }.let { rel ->
                // Avoid infinite loops on pathological layouts by guaranteeing progress.
                if (rel <= 0) 1 else rel
            }.coerceAtMost(remaining.length)

            val pageText = remaining.substring(0, endExclusive)
            pages += EpubPage(
                globalIndex = pages.size,
                chapterIndex = chapterIndex,
                chapterTitle = chapter.title,
                text = pageText,
                chapterStartOffset = start,
                isChapterStart = firstPageOfChapter,
            )
            start += endExclusive
            firstPageOfChapter = false
        }
        if (fullText.isEmpty()) {
            pages += EpubPage(
                globalIndex = pages.size,
                chapterIndex = chapterIndex,
                chapterTitle = chapter.title,
                text = "",
                chapterStartOffset = 0,
                isChapterStart = true,
            )
        }
    }

    return pages.ifEmpty {
        chapters.mapIndexed { index, chapter ->
            EpubPage(index, index, chapter.title, chapter.text, 0, true)
        }
    }
}

package com.example.vibereader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max

/**
 * One screen-sized slice of a chapter's raw text.
 *
 * A page is described entirely by character offsets into the chapter's original
 * text, so selection, highlights, speech offsets and persistence all keep using
 * chapter-relative coordinates. [endCharOffset] is exclusive.
 */
data class EpubPageSlice(
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val slicedText: String,
) {
    /** True when this slice is the first page of its chapter. */
    val isChapterStart: Boolean
        get() = startCharOffset == 0
}

/**
 * Computes a single unified list of viewport-sized page slices for all chapters.
 *
 * Pagination is line-bounded: the number of lines per page is derived from the
 * available pixel height divided by the explicit line height, then [TextMeasurer]
 * is constrained with `maxLines`. If incoming bounds are unconstrained/infinite,
 * screen dimensions are used as a finite fallback.
 *
 * Measurement runs on [Dispatchers.Default] so the UI main thread is never blocked.
 */
@Composable
fun rememberEpubPages(
    chapters: List<BookChapterEntity>,
    viewportWidthDp: Dp,
    viewportHeightDp: Dp,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    marginDp: Float,
): List<EpubPageSlice> {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val textMeasurer = rememberTextMeasurer()

    // BoxWithConstraints can report Infinity; always fall back to a finite screen size.
    val safeViewportWidthDp = when {
        viewportWidthDp == Dp.Infinity || viewportWidthDp.value.isNaN() || viewportWidthDp <= 0.dp ->
            configuration.screenWidthDp.dp
        else -> viewportWidthDp
    }
    val safeViewportHeightDp = when {
        viewportHeightDp == Dp.Infinity || viewportHeightDp.value.isNaN() || viewportHeightDp <= 0.dp ->
            configuration.screenHeightDp.dp
        else -> viewportHeightDp
    }

    val horizontalPaddingPx = with(density) { (marginDp.dp * 2).roundToPx() }
    val verticalPaddingPx = with(density) { 48.dp.roundToPx() } // 24dp top + 24dp bottom
    val maxWidthPx = with(density) { safeViewportWidthDp.roundToPx() }
        .minus(horizontalPaddingPx)
        .coerceAtLeast(with(density) { 1.dp.roundToPx() })

    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val rawHeightPx = with(density) { safeViewportHeightDp.roundToPx() }
    val finiteHeightPx = if (rawHeightPx <= 0 || rawHeightPx >= Constraints.Infinity) {
        screenHeightPx
    } else {
        rawHeightPx
    }
    // Bounded height fallback: usable height can never be <= 0.
    val usableHeightPx = maxOf(300, finiteHeightPx - verticalPaddingPx)

    val lineHeightPx = with(density) {
        if (lineHeight.isSp) lineHeight.toPx() else (fontSize.value * 1.6f).sp.toPx()
    }.takeIf { it.isFinite() && it > 0f } ?: 1f
    val titleSpacingPx = with(density) { 12.dp.toPx() }

    var pagesState by remember { mutableStateOf<List<EpubPageSlice>>(emptyList()) }
    // Serializes pagination runs: rapid settings changes can cancel a LaunchedEffect
    // while a synchronous TextMeasurer pass is still finishing, and TextMeasurer's
    // internal cache is not safe for concurrent use.
    val paginationMutex = remember { Mutex() }

    LaunchedEffect(chapters, maxWidthPx, usableHeightPx, fontSize, lineHeight, marginDp) {
        pagesState = paginationMutex.withLock {
            withContext(Dispatchers.Default) {
                paginateChaptersToSlices(
                    chapters = chapters,
                    textMeasurer = textMeasurer,
                    maxWidthPx = maxWidthPx,
                    maxHeightPx = usableHeightPx,
                    bodyStyle = TextStyle(fontSize = fontSize, lineHeight = lineHeight),
                    titleStyle = TextStyle(
                        fontSize = (fontSize.value * 1.35f).sp,
                        lineHeight = (lineHeight.value * 1.2f).sp,
                    ),
                    lineHeightPx = lineHeightPx,
                    titleSpacingPx = titleSpacingPx,
                )
            }
        }
    }

    return pagesState
}

/**
 * Flattens all chapters into line-bounded screen slices.
 *
 * Each slice is measured with finite [Constraints] and an explicit `maxLines`
 * derived from [lineHeightPx]. The first slice of a chapter reserves vertical
 * space for the chapter title. A hard `maxOf(1, ...)` advance guard makes the
 * slicing loop mathematically incapable of spinning forever.
 */
suspend fun paginateChaptersToSlices(
    chapters: List<BookChapterEntity>,
    textMeasurer: TextMeasurer,
    maxWidthPx: Int,
    maxHeightPx: Int,
    bodyStyle: TextStyle,
    titleStyle: TextStyle,
    lineHeightPx: Float = 0f,
    titleSpacingPx: Float = 0f,
): List<EpubPageSlice> = withContext(Dispatchers.Default) {
    if (chapters.isEmpty()) return@withContext emptyList<EpubPageSlice>()

    val safeMaxWidth = maxWidthPx.coerceAtLeast(1)
    val safeMaxHeight = sanitizeMaxHeight(maxHeightPx, bodyStyle.lineHeight.value)
    val safeLineHeight = resolveLineHeightPx(
        textMeasurer = textMeasurer,
        bodyStyle = bodyStyle,
        maxWidthPx = safeMaxWidth,
        preferredLineHeightPx = lineHeightPx,
    )
    val fullPageLines = floor(safeMaxHeight / safeLineHeight).toInt().coerceAtLeast(1)

    val slices = ArrayList<EpubPageSlice>(chapters.size * 2)
    val safeTitleSpacing = titleSpacingPx.takeIf { it.isFinite() && it >= 0f } ?: 0f

    for ((chapterIndex, chapter) in chapters.withIndex()) {
        // Stop promptly if a newer pagination request (settings change) superseded this one.
        currentCoroutineContext().ensureActive()
        val fullText = chapter.text
        if (fullText.isBlank()) {
            slices += EpubPageSlice(
                chapterIndex = chapterIndex,
                startCharOffset = 0,
                endCharOffset = 0,
                slicedText = "",
            )
            continue
        }

        val titleHeightPx = if (chapter.title.isNotBlank()) {
            textMeasurer.measure(
                text = AnnotatedString(chapter.title),
                style = titleStyle,
                constraints = Constraints(maxWidth = safeMaxWidth),
                overflow = TextOverflow.Clip,
                maxLines = TITLE_MAX_LINES,
            ).size.height + safeTitleSpacing
        } else {
            0f
        }
        val firstPageLines = floor(
            (safeMaxHeight - titleHeightPx).coerceAtLeast(safeLineHeight) / safeLineHeight,
        ).toInt().coerceIn(1, fullPageLines)

        var start = 0
        var firstPageOfChapter = true
        val textLength = fullText.length

        while (start < textLength) {
            currentCoroutineContext().ensureActive()
            val lineBudget = if (firstPageOfChapter) firstPageLines else fullPageLines
            val remaining = fullText.substring(start)
            val remainingLength = remaining.length
            if (remainingLength == 0) break

            val measured = textMeasurer.measure(
                text = AnnotatedString(remaining),
                style = bodyStyle,
                constraints = Constraints(maxWidth = safeMaxWidth, maxHeight = safeMaxHeight),
                overflow = TextOverflow.Clip,
                maxLines = lineBudget,
            )

            val charsFitted = if (measured.lineCount <= 0) {
                remainingLength
            } else {
                measured.getLineEnd(measured.lineCount - 1, visibleEnd = false)
                    .coerceIn(0, remainingLength)
            }

            // SAFETY GUARD: always advance by at least one character.
            val advance = maxOf(1, charsFitted).coerceAtMost(remainingLength)
            val end = start + advance

            slices += EpubPageSlice(
                chapterIndex = chapterIndex,
                startCharOffset = start,
                endCharOffset = end,
                slicedText = fullText.substring(start, end),
            )

            // Defensive: an advance of 0 can never happen, but never permit a stuck loop.
            if (end <= start) break
            start = end
            firstPageOfChapter = false
        }
    }

    slices
}

/** Resolves a finite, positive line height in pixels for line-bounded slicing. */
private fun resolveLineHeightPx(
    textMeasurer: TextMeasurer,
    bodyStyle: TextStyle,
    maxWidthPx: Int,
    preferredLineHeightPx: Float,
): Float {
    if (preferredLineHeightPx.isFinite() && preferredLineHeightPx > 0f) {
        return preferredLineHeightPx
    }
    val probe = textMeasurer.measure(
        text = AnnotatedString("Ag\nAg"),
        style = bodyStyle,
        constraints = Constraints(maxWidth = maxWidthPx.coerceAtLeast(1)),
        overflow = TextOverflow.Clip,
        maxLines = 2,
    )
    val measuredLineHeight = if (probe.lineCount >= 2) {
        probe.getLineTop(1).toFloat()
    } else {
        probe.size.height.toFloat() / probe.lineCount.coerceAtLeast(1)
    }
    return measuredLineHeight.takeIf { it.isFinite() && it > 0f } ?: 1f
}

private const val TITLE_MAX_LINES = 4

private fun sanitizeMaxHeight(maxHeightPx: Int, lineHeightSp: Float): Int {
    val minHeight = minLineHeightPx(lineHeightSp)
    return when {
        maxHeightPx <= 0 -> maxOf(300, minHeight)
        maxHeightPx >= Constraints.Infinity -> maxOf(300, minHeight * 40)
        else -> maxOf(300, maxHeightPx).coerceAtLeast(minHeight)
    }
}

private fun minLineHeightPx(lineHeightSp: Float): Int = max(1, lineHeightSp.toInt())

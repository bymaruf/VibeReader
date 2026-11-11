package com.example.vibereader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vibereader.data.room.BookChapterEntity

/**
 * Renders one chapter, or exactly one pre-computed viewport slice of a chapter.
 *
 * Horizontal mode always passes a [pageSlice]; in that case the renderer never
 * paginates or scrolls and maps every local selection/highlight offset back into
 * chapter-relative coordinates using [EpubPageSlice.startCharOffset].
 */
@Composable
fun EpubChapterRenderer(
    chapter: BookChapterEntity,
    chapterIndex: Int,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    marginDp: Float,
    foreground: Color,
    background: Color,
    selectionVersion: Int,
    highlightStartOffset: Int? = null,
    highlightEndOffset: Int? = null,
    selection: ReaderSelection? = null,
    onCanvasTap: () -> Unit,
    onSelectionChanged: (ReaderSelection?) -> Unit,
    modifier: Modifier = Modifier,
    /** When non-null, render only this line-bounded viewport slice. */
    pageSlice: EpubPageSlice? = null,
    /** Show the chapter title (typically only on the first slice of a chapter). */
    showTitle: Boolean = true,
    /** Enable vertical scrolling inside the page. Disabled for horizontal viewport pages. */
    scrollEnabled: Boolean = true,
) {
    val scrollState = rememberScrollState()
    val displayText = pageSlice?.slicedText ?: chapter.text
    val baseOffset = pageSlice?.startCharOffset?.coerceAtLeast(0) ?: 0
    val pageHighlightStart = highlightStartOffset?.let { it - baseOffset }
    val pageHighlightEnd = highlightEndOffset?.let { it - baseOffset }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(chapter.bookId, chapterIndex, baseOffset) {
                detectTapGestures(onTap = { onCanvasTap() })
            }
            .then(if (scrollEnabled) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(horizontal = marginDp.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showTitle) {
            Text(
                text = chapter.title,
                color = foreground,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = (fontSize.value * 1.35f).sp,
                    lineHeight = (lineHeight.value * 1.2f).sp,
                ),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (displayText.isEmpty()) {
            Text(
                text = "Empty Chapter",
                color = foreground,
                fontSize = fontSize,
                lineHeight = lineHeight,
            )
        } else {
            EpubSelectableText(
                chapter = chapter,
                chapterIndex = chapterIndex,
                displayText = displayText,
                chapterOffsetBase = baseOffset,
                fontSize = fontSize,
                lineHeight = lineHeight,
                foreground = foreground,
                selectionVersion = selectionVersion,
                highlightStartOffset = pageHighlightStart,
                highlightEndOffset = pageHighlightEnd,
                selection = selection,
                onSelectionChanged = onSelectionChanged,
            )
        }
    }
}

@Composable
fun EpubSelectableText(
    chapter: BookChapterEntity,
    chapterIndex: Int,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    foreground: Color,
    selectionVersion: Int,
    highlightStartOffset: Int? = null,
    highlightEndOffset: Int? = null,
    selection: ReaderSelection? = null,
    onSelectionChanged: (ReaderSelection?) -> Unit,
    /** Text rendered in this view; may be a viewport slice in horizontal mode. */
    displayText: String = chapter.text,
    /** Chapter-relative offset added to local selection indices to form [ReaderSelection] offsets. */
    chapterOffsetBase: Int = 0,
) {
    var value by remember(chapter.bookId, chapterIndex, chapterOffsetBase, displayText) {
        mutableStateOf(TextFieldValue(displayText))
    }
    LaunchedEffect(selectionVersion) {
        value = value.copy(selection = TextRange.Zero)
    }
    val highlightRange = when {
        highlightStartOffset != null && highlightEndOffset != null &&
            highlightEndOffset > highlightStartOffset -> {
            TextRange(
                highlightStartOffset.coerceIn(0, displayText.length),
                highlightEndOffset.coerceIn(0, displayText.length),
            )
        }
        selection?.chapterIndex == chapterIndex && selection.endOffset > selection.startOffset -> {
            val localStart = selection.startOffset - chapterOffsetBase
            val localEnd = selection.endOffset - chapterOffsetBase
            if (localEnd > 0 && localStart < displayText.length) {
                TextRange(
                    localStart.coerceIn(0, displayText.length),
                    localEnd.coerceIn(0, displayText.length),
                )
            } else {
                TextRange.Zero
            }
        }
        else -> TextRange.Zero
    }
    LaunchedEffect(highlightRange) {
        value = value.copy(selection = highlightRange)
    }
    BasicTextField(
        value = value,
        onValueChange = { updated ->
            value = updated
            val localStart = minOf(updated.selection.start, updated.selection.end)
                .coerceIn(0, updated.text.length)
            val localEnd = maxOf(updated.selection.start, updated.selection.end)
                .coerceIn(0, updated.text.length)
            if (localEnd > localStart) {
                onSelectionChanged(
                    ReaderSelection(
                        chapterIndex = chapterIndex,
                        chapterTitle = chapter.title,
                        text = updated.text.substring(localStart, localEnd),
                        startOffset = chapterOffsetBase + localStart,
                        endOffset = chapterOffsetBase + localEnd,
                    ),
                )
            } else {
                onSelectionChanged(null)
            }
        },
        readOnly = true,
        textStyle = TextStyle(
            color = foreground,
            fontSize = fontSize,
            lineHeight = lineHeight,
        ),
        cursorBrush = SolidColor(foreground),
        modifier = Modifier.fillMaxWidth(),
    )
}

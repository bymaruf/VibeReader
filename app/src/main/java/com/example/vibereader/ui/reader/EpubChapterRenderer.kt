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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.example.vibereader.data.room.BookChapterEntity

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
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(chapter.bookId, chapterIndex) {
                detectTapGestures(onTap = { onCanvasTap() })
            }
            .verticalScroll(scrollState)
            .padding(horizontal = marginDp.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = chapter.title,
            color = foreground,
            style = MaterialTheme.typography.titleLarge,
        )
        if (chapter.text.isBlank()) {
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
                fontSize = fontSize,
                lineHeight = lineHeight,
                foreground = foreground,
                selectionVersion = selectionVersion,
                highlightStartOffset = highlightStartOffset,
                highlightEndOffset = highlightEndOffset,
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
) {
    var value by remember(chapter.bookId, chapterIndex) {
        mutableStateOf(TextFieldValue(chapter.text))
    }
    LaunchedEffect(selectionVersion) {
        value = value.copy(selection = TextRange.Zero)
    }
    val highlightRange = when {
        highlightStartOffset != null && highlightEndOffset != null &&
            highlightEndOffset > highlightStartOffset -> {
            TextRange(
                highlightStartOffset.coerceIn(0, chapter.text.length),
                highlightEndOffset.coerceIn(0, chapter.text.length),
            )
        }
        selection?.chapterIndex == chapterIndex && selection.endOffset > selection.startOffset -> {
            TextRange(
                selection.startOffset.coerceIn(0, chapter.text.length),
                selection.endOffset.coerceIn(0, chapter.text.length),
            )
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
            val start = minOf(updated.selection.start, updated.selection.end)
                .coerceIn(0, updated.text.length)
            val end = maxOf(updated.selection.start, updated.selection.end)
                .coerceIn(0, updated.text.length)
            if (end > start) {
                onSelectionChanged(
                    ReaderSelection(
                        chapterIndex = chapterIndex,
                        chapterTitle = chapter.title,
                        text = updated.text.substring(start, end),
                        startOffset = start,
                        endOffset = end,
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

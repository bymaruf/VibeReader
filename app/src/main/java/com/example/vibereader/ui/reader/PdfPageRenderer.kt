package com.example.vibereader.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.vibereader.model.ReadingTheme
import com.example.vibereader.util.PdfColorMatrix

@Composable
fun PdfPageRenderer(
    pageIndex: Int,
    bitmap: Bitmap?,
    title: String,
    theme: ReadingTheme,
    selection: ReaderSelection?,
    speechSelection: ReaderSelection?,
    savedHighlights: List<com.example.vibereader.data.room.HighlightEntity>,
    aiProcessingPageIndex: Int?,
    fitToBounds: Boolean,
    modifier: Modifier = Modifier,
    onRenderPage: (
        pageIndex: Int,
        requestedWidthPx: Int,
        requestedHeightPx: Int,
        zoomScale: Float,
    ) -> Unit,
    onCanvasTap: () -> Unit,
    onTextLongPress: (pageIndex: Int, xFraction: Float, yFraction: Float) -> Unit,
    onSelectionHandleDragEnd: (
        pageIndex: Int,
        isStartHandle: Boolean,
        xFraction: Float,
        yFraction: Float,
    ) -> Unit,
    onZoomChanged: (Float) -> Unit,
) {
    val backgroundColor = when (theme) {
        ReadingTheme.SYSTEM -> if (isSystemInDarkTheme()) Color(0xFF121212) else Color.White
        ReadingTheme.OLED_BLACK -> Color.Black
        ReadingTheme.SEPIA -> Color(0xFFF8F1E5)
        ReadingTheme.LIGHT -> Color.White
    }
    val pdfColorFilter = when (theme) {
        ReadingTheme.OLED_BLACK -> PdfColorMatrix.OLED_NIGHT_FILTER
        ReadingTheme.SEPIA -> PdfColorMatrix.SEPIA_FILTER
        ReadingTheme.SYSTEM,
        ReadingTheme.LIGHT,
        -> null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = if (fitToBounds) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val availableWidthPx = constraints.maxWidth
            val availableHeightPx = constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: 0
            LaunchedEffect(pageIndex, availableWidthPx, availableHeightPx) {
                if (availableWidthPx > 0) {
                    onRenderPage(pageIndex, availableWidthPx, availableHeightPx, 1f)
                }
            }

            if (bitmap == null) {
                CircularProgressIndicator(color = Color.Gray)
            } else {
                SafeZoomablePdfCanvas(
                    pageIndex = pageIndex,
                    bitmap = bitmap,
                    contentDescription = "PDF Page ${pageIndex + 1} from $title",
                    colorFilter = pdfColorFilter,
                    selection = selection?.takeIf { it.chapterIndex == pageIndex },
                    savedHighlights = savedHighlights.filter { it.pageIndex == pageIndex },
                    showProcessingBoundary = aiProcessingPageIndex == pageIndex,
                    selectionRects = buildList {
                        addAll(
                            selection
                                ?.takeIf { it.chapterIndex == pageIndex }
                                ?.pdfHighlightRects
                                .orEmpty(),
                        )
                        addAll(
                            speechSelection
                                ?.takeIf { it.chapterIndex == pageIndex }
                                ?.pdfHighlightRects
                                .orEmpty(),
                        )
                    },
                    fitToBounds = fitToBounds,
                    onCanvasTap = onCanvasTap,
                    onTextLongPress = onTextLongPress,
                    onSelectionHandleDragEnd = onSelectionHandleDragEnd,
                    onZoomChanged = { zoomScale ->
                        onZoomChanged(zoomScale)
                        if (availableWidthPx > 0) {
                            onRenderPage(
                                pageIndex,
                                availableWidthPx,
                                availableHeightPx,
                                zoomScale,
                            )
                        }
                    },
                )
            }
        }
    }
}

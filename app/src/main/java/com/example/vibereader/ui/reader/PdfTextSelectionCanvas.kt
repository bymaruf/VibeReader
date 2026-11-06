package com.example.vibereader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun PdfTextSelectionCanvas(
    pageIndex: Int,
    scale: Float,
    offset: Offset,
    pageWidthPx: Int,
    pageHeightPx: Int,
    fitToBounds: Boolean,
    onCanvasTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onTextLongPress: (pageIndex: Int, xFraction: Float, yFraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(offset)
    val currentOnCanvasTap by rememberUpdatedState(onCanvasTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnTextLongPress by rememberUpdatedState(onTextLongPress)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(pageIndex) {
                detectTapGestures(
                    onTap = { currentOnCanvasTap() },
                    onDoubleTap = { currentOnDoubleTap() },
                    onLongPress = { touch ->
                        val zoom = currentScale
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        if (
                            !zoom.isFinite() || zoom <= 0f ||
                            width <= 0f || height <= 0f
                        ) {
                            return@detectTapGestures
                        }

                        val center = Offset(width / 2f, height / 2f)
                        val canvasPoint = (touch - center - currentOffset) / zoom + center
                        val contentWidth: Float
                        val contentHeight: Float
                        if (fitToBounds && pageWidthPx > 0 && pageHeightPx > 0) {
                            val fitScale = minOf(
                                width / pageWidthPx,
                                height / pageHeightPx,
                            )
                            contentWidth = pageWidthPx * fitScale
                            contentHeight = pageHeightPx * fitScale
                        } else {
                            contentWidth = width
                            contentHeight = height
                        }
                        val left = (width - contentWidth) / 2f
                        val top = (height - contentHeight) / 2f
                        val pagePoint = Offset(
                            x = (canvasPoint.x - left) / contentWidth * width,
                            y = (canvasPoint.y - top) / contentHeight * height,
                        )
                        if (
                            !pagePoint.x.isFinite() || !pagePoint.y.isFinite() ||
                            canvasPoint.x !in left..(left + contentWidth) ||
                            canvasPoint.y !in top..(top + contentHeight)
                        ) {
                            return@detectTapGestures
                        }
                        currentOnTextLongPress(
                            pageIndex,
                            pagePoint.x / width,
                            pagePoint.y / height,
                        )
                    },
                )
            },
    )
}

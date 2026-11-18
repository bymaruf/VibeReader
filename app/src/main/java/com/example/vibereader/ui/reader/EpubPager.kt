package com.example.vibereader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Horizontal, viewport-by-viewport pager for EPUB content.
 *
 * [pageCount] is the total number of calculated viewport pages (not chapters).
 * Swiping left/right always transitions to the adjacent viewport page.
 */
@Composable
fun EpubPager(
    pageCount: Int,
    currentPageIndex: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onCanvasTap: () -> Unit = {},
    content: @Composable (pageIndex: Int) -> Unit,
) {
    if (pageCount <= 0) return

    val pagerState = rememberPagerState(
        initialPage = currentPageIndex.coerceIn(0, pageCount - 1),
        pageCount = { pageCount },
    )

    LaunchedEffect(currentPageIndex, pageCount) {
        val targetPage = currentPageIndex.coerceIn(0, pageCount - 1)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState, pageCount) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect(onPageChanged)
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        pageSpacing = 0.dp,
        userScrollEnabled = true,
        contentPadding = PaddingValues(0.dp),
    ) { page ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(page) {
                    detectTapGestures(onTap = { onCanvasTap() })
                },
        ) {
            content(page)
        }
    }
}

package com.example.vibereader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun EpubPager(
    chapterCount: Int,
    currentChapterIndex: Int,
    onChapterChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (chapterIndex: Int) -> Unit,
) {
    if (chapterCount <= 0) return

    val pagerState = rememberPagerState(
        initialPage = currentChapterIndex.coerceIn(0, chapterCount - 1),
        pageCount = { chapterCount },
    )

    LaunchedEffect(currentChapterIndex, chapterCount) {
        val targetChapter = currentChapterIndex.coerceIn(0, chapterCount - 1)
        if (pagerState.currentPage != targetChapter) {
            pagerState.animateScrollToPage(targetChapter)
        }
    }

    LaunchedEffect(pagerState, chapterCount) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect(onChapterChanged)
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        pageSpacing = 0.dp,
        userScrollEnabled = true,
        contentPadding = PaddingValues(0.dp),
    ) { page ->
        Box(modifier = Modifier.fillMaxSize()) {
            content(page)
        }
    }
}

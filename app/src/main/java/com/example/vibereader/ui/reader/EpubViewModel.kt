package com.example.vibereader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * EPUB paging state holder (decoupled from chapter count).
 *
 * Navigation and progress are always based on the total number of calculated
 * [EpubPageSlice]s across all chapters, never on `chapters.size`.
 */
class EpubViewModel {
    var allPages: List<EpubPageSlice> by mutableStateOf(emptyList())
        private set

    /** Total calculated screen pages across all chapters combined. */
    val totalCalculatedPages: Int
        get() = allPages.size

    var currentPageIndex: Int by mutableIntStateOf(0)
        private set

    /**
     * Character anchor of the last visible slice. Retained when a viewport
     * change temporarily empties [allPages], so repagination can restore the
     * same chapter/character position instead of the old numeric page index.
     */
    var lastAnchor: EpubPageSlice? by mutableStateOf(null)
        private set

    /** Progress bound to (currentPageIndex + 1) / totalCalculatedPages. */
    val progress: Float
        get() = if (totalCalculatedPages > 0) {
            (currentPageIndex + 1).toFloat() / totalCalculatedPages.toFloat()
        } else {
            0f
        }

    val currentChapterIndex: Int
        get() = allPages.getOrNull(safeCurrentIndex)?.chapterIndex ?: 0

    val currentSlice: EpubPageSlice?
        get() = allPages.getOrNull(safeCurrentIndex)

    private val safeCurrentIndex: Int
        get() = if (allPages.isEmpty()) 0 else currentPageIndex.coerceIn(0, allPages.lastIndex)

    fun setCalculatedPages(pages: List<EpubPageSlice>, restoreIndex: Int = currentPageIndex) {
        if (pages.isNotEmpty()) {
            lastAnchor = pages.getOrNull(restoreIndex.coerceIn(0, pages.lastIndex))
        }
        allPages = pages
        currentPageIndex = if (pages.isEmpty()) 0 else restoreIndex.coerceIn(0, pages.lastIndex)
    }

    fun onPageChanged(pageIndex: Int) {
        if (allPages.isEmpty()) {
            currentPageIndex = 0
            return
        }
        currentPageIndex = pageIndex.coerceIn(0, allPages.lastIndex)
        lastAnchor = allPages.getOrNull(currentPageIndex)
    }

    fun restoreFromProgress(savedProgress: Float, savedPageIndexHint: Int = -1) {
        if (allPages.isEmpty()) return
        val target = when {
            savedPageIndexHint in allPages.indices -> savedPageIndexHint
            savedProgress in 0f..1f -> ((savedProgress * allPages.size) - 1f).toInt()
            else -> 0
        }.coerceIn(0, allPages.lastIndex)
        currentPageIndex = target
        lastAnchor = allPages.getOrNull(target)
    }

    fun jumpToChapter(chapterIndex: Int) {
        val target = allPages.indexOfFirst { it.chapterIndex == chapterIndex }
        if (target >= 0) currentPageIndex = target
    }
}

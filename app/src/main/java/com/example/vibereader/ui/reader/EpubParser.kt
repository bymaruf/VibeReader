package com.example.vibereader.ui.reader

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import com.example.vibereader.data.room.BookChapterEntity

/**
 * EPUB paging/parser helper (Native Compose).
 *
 * Page navigation is decoupled from chapter count: the total number of pages is
 * the sum of line-bounded viewport slices across all chapters combined.
 */
object EpubParser {

    /**
     * Flattens all chapters into line-bounded [EpubPageSlice]s.
     *
     * Delegates to the canonical paginator in [paginateChaptersToSlices] so there is
     * exactly one slicing algorithm in the app. Measurement runs off the UI thread.
     */
    suspend fun paginateToScreenPages(
        chapters: List<BookChapterEntity>,
        textMeasurer: TextMeasurer,
        maxWidthPx: Int,
        maxHeightPx: Int,
        bodyStyle: TextStyle,
        titleStyle: TextStyle,
    ): List<EpubPageSlice> = paginateChaptersToSlices(
        chapters = chapters,
        textMeasurer = textMeasurer,
        maxWidthPx = maxWidthPx,
        maxHeightPx = maxHeightPx,
        bodyStyle = bodyStyle,
        titleStyle = titleStyle,
    )
}

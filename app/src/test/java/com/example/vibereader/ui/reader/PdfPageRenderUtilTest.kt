package com.example.vibereader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageRenderUtilTest {
    @Test
    fun calculateFitScaleFitsPortraitPageWithinContainer() {
        assertEquals(
            1.8f,
            calculateFitScale(
                pageWidth = 600,
                pageHeight = 900,
                containerWidth = 1080,
                containerHeight = 1920,
            ),
            0.001f,
        )
    }

    @Test
    fun calculateFitScaleFitsWidePageWithinContainer() {
        assertEquals(
            0.675f,
            calculateFitScale(
                pageWidth = 1600,
                pageHeight = 900,
                containerWidth = 1080,
                containerHeight = 1920,
            ),
            0.001f,
        )
    }

    @Test
    fun calculateFitScaleRejectsInvalidDimensions() {
        assertEquals(
            0f,
            calculateFitScale(
                pageWidth = 0,
                pageHeight = 900,
                containerWidth = 1080,
                containerHeight = 1920,
            ),
            0f,
        )
    }
}

package com.example.vibereader.ui.reader

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSelectionGeometryTest {

    @Test
    fun `fit viewport centres a letterboxed page`() {
        val viewport = pdfPageViewport(
            canvasWidthPx = 400f,
            canvasHeightPx = 800f,
            pageWidthPx = 200,
            pageHeightPx = 200,
            fitToBounds = true,
        )

        assertEquals(0f, viewport!!.left, 0.001f)
        assertEquals(200f, viewport.top, 0.001f)
        assertEquals(400f, viewport.width, 0.001f)
        assertEquals(400f, viewport.height, 0.001f)
    }

    @Test
    fun `fraction maps from content point and rejects outside touches`() {
        val viewport = pdfPageViewport(
            canvasWidthPx = 400f,
            canvasHeightPx = 800f,
            pageWidthPx = 200,
            pageHeightPx = 400,
            fitToBounds = true,
        )!!

        val fraction = viewport.fractionAt(Offset(200f, 400f))
        assertEquals(0.5f, fraction!!.x, 0.001f)
        assertEquals(0.5f, fraction.y, 0.001f)
        assertNull(viewport.fractionAt(Offset(-1f, 400f)))
        assertNull(viewport.fractionAt(Offset(200f, 900f)))
    }

    @Test
    fun `screen and content transforms are inverses under zoom and pan`() {
        val screen = Offset(320f, 640f)
        val pan = Offset(24f, -18f)
        val content = screenToContentPoint(
            touch = screen,
            canvasWidthPx = 400f,
            canvasHeightPx = 800f,
            scale = 2.5f,
            pan = pan,
        )
        val roundTrip = contentToScreenPoint(
            contentPoint = content,
            canvasWidthPx = 400f,
            canvasHeightPx = 800f,
            scale = 2.5f,
            pan = pan,
        )

        assertEquals(screen.x, roundTrip.x, 0.001f)
        assertEquals(screen.y, roundTrip.y, 0.001f)
    }

    @Test
    fun `merging joins glyphs on the same line only`() {
        val merged = mergePdfSelectionRects(
            listOf(
                PdfSelectionRect(0.10f, 0.10f, 0.20f, 0.14f),
                PdfSelectionRect(0.21f, 0.10f, 0.30f, 0.14f),
                PdfSelectionRect(0.10f, 0.20f, 0.18f, 0.24f),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals(0.10f, merged[0].left, 0.0001f)
        assertEquals(0.30f, merged[0].right, 0.0001f)
        assertEquals(0.10f, merged[1].left, 0.0001f)
    }

    @Test
    fun `highlight hit testing uses normalized rects`() {
        val rects = listOf(PdfSelectionRect(0.2f, 0.3f, 0.6f, 0.4f))

        assertTrue(hitTestPdfSelectionRects(rects, 0.4f, 0.35f))
        assertFalse(hitTestPdfSelectionRects(rects, 0.7f, 0.35f))
    }

    @Test
    fun `handle hit testing follows the pin below the anchor`() {
        val viewport = PdfPageViewport(left = 0f, top = 0f, width = 100f, height = 100f)
        val start = PdfSelectionPoint(0.2f, 0.3f)
        val end = PdfSelectionPoint(0.8f, 0.3f)

        val onStartPin = Offset(20f, 35f)
        assertEquals(
            PdfSelectionHandleSide.START,
            hitTestPdfSelectionHandle(
                contentPoint = onStartPin,
                viewport = viewport,
                startHandle = start,
                endHandle = end,
                stemLengthPx = 5f,
                touchRadiusPx = 12f,
            ),
        )
        assertEquals(
            PdfSelectionHandleSide.END,
            hitTestPdfSelectionHandle(
                contentPoint = Offset(80f, 35f),
                viewport = viewport,
                startHandle = start,
                endHandle = end,
                stemLengthPx = 5f,
                touchRadiusPx = 12f,
            ),
        )
        assertNull(
            hitTestPdfSelectionHandle(
                contentPoint = Offset(50f, 35f),
                viewport = viewport,
                startHandle = start,
                endHandle = end,
                stemLengthPx = 5f,
                touchRadiusPx = 12f,
            ),
        )
    }

    @Test
    fun `normalized selection rect converts to content coordinates`() {
        val viewport = PdfPageViewport(left = 10f, top = 20f, width = 200f, height = 400f)
        val rect = viewport.contentRect(
            PdfSelectionRect(0.25f, 0.5f, 0.75f, 0.625f),
        )

        assertEquals(60f, rect!!.left, 0.001f)
        assertEquals(220f, rect.top, 0.001f)
        assertEquals(160f, rect.right, 0.001f)
        assertEquals(270f, rect.bottom, 0.001f)
    }
}

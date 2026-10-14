package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.ui.preview.FileSamples
import dev.hermeskotlin.ui.preview.pdfPage
import dev.hermeskotlin.ui.preview.samplePdf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfViewTest {

    @Test
    fun pagesAreDrawnInStepsAsWideAsShown() {
        assertEquals(1024, renderWidth(1000, aspect = 0.77f))
        assertEquals(1024, renderWidth(1024, aspect = 0.77f))
        assertEquals(1280, renderWidth(1025, aspect = 0.77f))
        assertEquals(256, renderWidth(1, aspect = 0.77f))
    }

    @Test
    fun aDeepZoomOrATallPageStaysWithinMemory() {
        // A Letter page zoomed four times on a 1080 px screen: capped near 8 million pixels.
        val letter = 612f / 792f
        val width = renderWidth(4320, letter)
        assertTrue(width < 4320)
        assertTrue(width * (width / letter) <= 8_000_000.0)
        // A receipt-long page is drawn narrower still.
        val tall = renderWidth(1080, aspect = 0.1f)
        assertTrue(tall * (tall / 0.1f) <= 8_000_000.0, "$tall")
        assertTrue(renderWidth(10, aspect = 0.0001f) >= 1)
    }

    @Test
    fun theSamplePdfPointsAtEachObject() {
        val text = samplePdf(listOf(pdfPage { text(56, 700, "Hello (world)") })).decodeToString()
        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue("(Hello \\(world\\)) Tj" in text)
        val xrefAt = text.substringAfter("startxref\n").substringBefore('\n').toInt()
        assertTrue(text.startsWith("xref\n", xrefAt))
        val entries = text.substring(xrefAt).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(6, entries.size)
        entries.forEachIndexed { index, entry ->
            assertTrue(text.startsWith("${index + 1} 0 obj\n", entry.substring(0, 10).toInt()), entry)
        }
        // Each stream's /Length is its content's length.
        Regex("""<< /Length (\d+) >>\nstream\n""").findAll(text).forEach { match ->
            val start = match.range.last + 1
            assertTrue(text.startsWith("\nendstream", start + match.groupValues[1].toInt()))
        }
    }

    @Test
    fun theGallerySampleHasTwoPages() {
        val text = FileSamples.pdf.decodeToString()
        assertTrue("/Count 2" in text)
        assertTrue(FileSamples.pdf.all { it >= 0 }, "ASCII only, so offsets are byte offsets")
    }
}

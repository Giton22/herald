package dev.hermeskotlin.ui.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ScreenCaptureTest {

    private val waiting = ScreenCapture(pending = 2, callUp = 3)

    @Test
    fun partsForThisCallUpFillItIn() {
        val text = waiting.withText(3, "Settings", listOf(ScreenItem("Apps")))
        assertEquals(listOf("Apps"), text.items.map { it.text })
        assertEquals(1, text.pending)
        val both = text.withScreenshot(3, byteArrayOf(1), 1344, 2992)
        assertEquals(0, both.pending)
        assertEquals(1344 to 2992, both.displayWidth to both.displayHeight)
    }

    @Test
    fun aLatePartFromAnEarlierCallUpIsTurnedAway() {
        assertSame(waiting, waiting.withText(2, "Chrome", listOf(ScreenItem("Old page"))))
        val shot = waiting.withScreenshot(2, byteArrayOf(1), 1344, 2992)
        assertSame(waiting, shot)
        assertNull(shot.screenshot)
        assertEquals(2, shot.pending)
    }
}

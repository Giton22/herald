package dev.hermeskotlin.ui.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun aScreenThatNeverComesIsGivenUpAndMarkedMissed() {
        // Android's assistant settings off: the flags promise both parts and neither arrives.
        val given = waiting.givenUp(3)
        assertEquals(0, given.pending)
        assertTrue(given.missed)
        // A part that turns up after all is still taken.
        val late = given.withText(3, "Settings", listOf(ScreenItem("Apps")))
        assertFalse(late.missed)
        assertEquals(listOf("Apps"), late.items.map { it.text })
    }

    @Test
    fun givingUpKeepsWhatCameAndLeavesOtherCallUpsAlone() {
        val textOnly = waiting.withText(3, "Settings", listOf(ScreenItem("Apps"))).givenUp(3)
        assertEquals(0, textOnly.pending)
        assertFalse(textOnly.missed)
        assertSame(waiting, waiting.givenUp(2))
        val arrived = ScreenCapture(callUp = 3)
        assertSame(arrived, arrived.givenUp(3))
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

package dev.hermeskotlin.desktop

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowMemoryTest {

    @Test
    fun aWindowReadsBackAsSaved() {
        val saved = SavedWindow(-1200, 40, 1100, 780, maximized = true)
        assertEquals(saved, SavedWindow.decode(saved.encode()))
    }

    @Test
    fun anythingElseReadsAsNone() {
        assertNull(SavedWindow.decode(""))
        assertNull(SavedWindow.decode("1,2,3,4"))
        assertNull(SavedWindow.decode("a,2,800,600,false"))
        assertNull(SavedWindow.decode("0,0,40,600,false"))
    }

    @Test
    fun aWindowOnAMonitorThatWentIsNotReopenedThere() {
        val laptop = listOf(Rectangle(0, 0, 1920, 1080))
        assertTrue(SavedWindow(100, 100, 800, 600, false).onScreen(laptop))
        assertFalse(SavedWindow(2400, 100, 800, 600, false).onScreen(laptop))
        // Only a few pixels of its title strip left on the screen: too little to grab.
        assertFalse(SavedWindow(1915, 100, 800, 600, false).onScreen(laptop))
        assertTrue(SavedWindow(1700, 100, 800, 600, false).onScreen(laptop))
    }
}

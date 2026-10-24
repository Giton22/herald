package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietHoursTest {

    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun offByDefault() {
        val fresh = AppSettings()
        assertFalse(fresh.quietHours)
        // The default window would hold 03:00, but nothing is quiet until the switch is on.
        assertFalse(fresh.quietNow(at(3)))
        assertTrue(fresh.copy(quietHours = true).quietNow(at(3)))
    }

    @Test
    fun aWindowInsideOneDay() {
        val from = at(13)
        val until = at(14, 30)
        assertFalse(inQuietWindow(from, until, at(12, 59)))
        assertTrue(inQuietWindow(from, until, at(13)))
        assertTrue(inQuietWindow(from, until, at(14, 29)))
        // The end is when it rings again.
        assertFalse(inQuietWindow(from, until, at(14, 30)))
        assertFalse(inQuietWindow(from, until, at(0)))
    }

    @Test
    fun aWindowAcrossMidnight() {
        val from = at(22)
        val until = at(7)
        assertFalse(inQuietWindow(from, until, at(21, 59)))
        assertTrue(inQuietWindow(from, until, at(22)))
        assertTrue(inQuietWindow(from, until, at(23, 59)))
        assertTrue(inQuietWindow(from, until, at(0)))
        assertTrue(inQuietWindow(from, until, at(6, 59)))
        assertFalse(inQuietWindow(from, until, at(7)))
        assertFalse(inQuietWindow(from, until, at(12)))
    }

    @Test
    fun aWindowThatStartsWhereItEndsIsEmpty() {
        assertFalse(inQuietWindow(at(8), at(8), at(8)))
        assertFalse(inQuietWindow(at(8), at(8), at(20)))
    }

    @Test
    fun startingAtMidnight() {
        assertTrue(inQuietWindow(at(0), at(6), at(0)))
        assertFalse(inQuietWindow(at(0), at(6), at(23, 59)))
        assertTrue(inQuietWindow(at(18), at(0), at(23, 59)))
        assertFalse(inQuietWindow(at(18), at(0), at(0)))
    }

    @Test
    fun clockTimes() {
        assertEquals("22:00", clockTime(at(22)))
        assertEquals("07:05", clockTime(at(7, 5)))
        assertEquals("00:00", clockTime(at(24)))
    }

    @Test
    fun aQuietCallSaysSo() {
        assertEquals("quiet", CallOutcome.Quiet.status)
    }
}

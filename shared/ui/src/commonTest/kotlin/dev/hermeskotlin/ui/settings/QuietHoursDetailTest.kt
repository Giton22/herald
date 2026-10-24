package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertTrue

class QuietHoursDetailTest {

    @Test
    fun saysTheWindowOnceOn() {
        val on = quietHoursDetail(AppSettings(quietHours = true, quietFrom = 22 * 60, quietUntil = 7 * 60 + 30))
        assertTrue(on.startsWith("Nothing rings from 22:00 to 07:30."), on)
        assertTrue(on.endsWith(DND_DETAIL), on)
    }

    @Test
    fun aWindowThatStartsWhereItEndsSaysNothingIsQuiet() {
        val empty = quietHoursDetail(AppSettings(quietHours = true, quietFrom = 7 * 60, quietUntil = 7 * 60))
        assertTrue(empty.startsWith("They start and end at 07:00, so nothing is quiet yet"), empty)
    }

    @Test
    fun offItSaysWhatItWouldDo() {
        val off = quietHoursDetail(AppSettings())
        assertTrue(off.startsWith("Nothing rings between the times you pick."), off)
        assertTrue("Do Not Disturb" in off, off)
    }
}

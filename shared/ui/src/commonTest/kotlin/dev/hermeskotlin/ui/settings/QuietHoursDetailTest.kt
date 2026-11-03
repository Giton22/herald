package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class QuietHoursDetailTest {

    @Test
    fun saysTheWindowOnceOn() {
        assertEquals(QuietWindow.Window, quietWindow(AppSettings(quietHours = true, quietFrom = 22 * 60, quietUntil = 7 * 60 + 30)))
    }

    @Test
    fun aWindowThatStartsWhereItEndsSaysNothingIsQuiet() {
        assertEquals(QuietWindow.Empty, quietWindow(AppSettings(quietHours = true, quietFrom = 7 * 60, quietUntil = 7 * 60)))
    }

    @Test
    fun offItSaysWhatItWouldDo() {
        assertEquals(QuietWindow.Off, quietWindow(AppSettings()))
        // Off, a window that starts where it ends isn't called out: there's nothing to be quiet yet anyway.
        assertEquals(QuietWindow.Off, quietWindow(AppSettings(quietHours = false, quietFrom = 7 * 60, quietUntil = 7 * 60)))
    }
}

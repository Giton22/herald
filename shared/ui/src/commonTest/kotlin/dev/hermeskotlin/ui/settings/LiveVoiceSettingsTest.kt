package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveVoiceSettingsTest {

    @Test
    fun thePlansWindowsAreNamedInEnglish() {
        assertEquals("5-hour limit", windowName("5h", 300))
        assertEquals("This week", windowName("semanal", 7 * 1440))
        assertEquals("This week", windowName("weekly", null))
        assertEquals("monthly", windowName("monthly", null))
    }

    @Test
    fun minutesReadNaturally() {
        assertEquals("0 min", minutesLabel(0.0))
        assertEquals("under a minute", minutesLabel(0.4))
        assertEquals("12 min", minutesLabel(12.4))
    }

    @Test
    fun theSettingsRowSaysWhatIsPicked() {
        assertEquals("On, in the gateway's voice.", liveVoiceDetail(AppSettings()))
        assertEquals("On, in Maple.", liveVoiceDetail(AppSettings(subscriptionVoiceName = "maple")))
        assertEquals("Off. A voice chat is the profile's own.", liveVoiceDetail(AppSettings(subscriptionVoice = false, subscriptionVoiceName = "maple")))
    }
}

package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneSwitchesTest {

    @Test
    fun nothingIsOnUntilTurnedOn() {
        val fresh = AppSettings()
        assertTrue(fresh.enabledPhoneTools().isEmpty())
        assertFalse(fresh.ringsForAnswers)
    }

    @Test
    fun ringingNeedsItsOwnSwitchAndTheOneForAll() {
        assertFalse(AppSettings(phoneTools = true).ringsForAnswers)
        // Its own switch on, the one for all of them off: nothing rings, as no tool works.
        assertFalse(AppSettings(phoneRings = true).ringsForAnswers)
        assertTrue(AppSettings(phoneTools = true, phoneRings = true).ringsForAnswers)
    }

    @Test
    fun ringingIsNoToolTheGatewayHears() {
        // The phone rings by itself: the gateway is never told about it, and it doesn't count as a tool.
        val on = AppSettings(phoneTools = true, phoneRings = true, phoneToolsOn = listOf(PhoneTool.Battery.id))
        assertEquals(setOf(PhoneTool.Battery), on.enabledPhoneTools())
    }
    @Test
    fun permissionDefaultsKeepExistingConfirmingToolsOn() {
        val defaults = AppSettings()
        val changes = setOf(PhoneTool.Sound, PhoneTool.Alarms, PhoneTool.Open, PhoneTool.Clipboard, PhoneTool.Geofences, PhoneTool.Calendar)
        for (tool in PhoneTool.entries) {
            assertEquals(tool in changes, defaults.requiresPhoneToolPermission(tool), tool.id)
        }
    }

    @Test
    fun everyToolCanOverridePermissionWithoutGrantingUse() {
        for (tool in PhoneTool.entries) {
            val ask = AppSettings(phoneToolsAsk = mapOf(tool.id to true))
            assertTrue(ask.requiresPhoneToolPermission(tool))
            assertTrue(ask.enabledPhoneTools().isEmpty())
            assertFalse(ask.copy(phoneToolsAsk = mapOf(tool.id to false)).requiresPhoneToolPermission(tool))
        }
    }

}

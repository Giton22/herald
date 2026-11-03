package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectedDevicesSummaryTest {

    @Test
    fun theRowSaysWhetherThePebbleIsOnAndAnswers() {
        assertEquals(ConnectedDevicesLine.PebbleOn, connectedDevicesLine(AppSettings()))
        assertEquals(ConnectedDevicesLine.PebbleAnswers, connectedDevicesLine(AppSettings(pebbleAnswers = true)))
        // Off, answering there doesn't count either.
        assertEquals(ConnectedDevicesLine.PebbleOff, connectedDevicesLine(AppSettings(pebble = false, pebbleAnswers = true)))
    }
}

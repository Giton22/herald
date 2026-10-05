package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.settings.RunningSend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendModeTest {

    @Test
    fun withNothingRunningItIsAPlainPrompt() {
        assertNull(sendModeFor(running = false, picked = RunningSend.StopAndSend, setting = RunningSend.Queue, withAttachments = false))
    }

    @Test
    fun aTapMidTurnFollowsTheSettingWhichDefaultsToSteer() {
        assertEquals(RunningSend.Steer, sendModeFor(running = true, picked = null, setting = null, withAttachments = false))
        assertEquals(RunningSend.Queue, sendModeFor(running = true, picked = null, setting = RunningSend.Queue, withAttachments = false))
        assertEquals(RunningSend.StopAndSend, sendModeFor(running = true, picked = null, setting = RunningSend.StopAndSend, withAttachments = false))
    }

    @Test
    fun aModePickedFromTheMenuBeatsTheSetting() {
        assertEquals(RunningSend.StopAndSend, sendModeFor(running = true, picked = RunningSend.StopAndSend, setting = RunningSend.Steer, withAttachments = false))
    }

    @Test
    fun aSteerWithFilesIsQueuedSinceSteeringCantCarryThem() {
        assertEquals(RunningSend.Queue, sendModeFor(running = true, picked = null, setting = RunningSend.Steer, withAttachments = true))
        assertEquals(RunningSend.StopAndSend, sendModeFor(running = true, picked = RunningSend.StopAndSend, setting = null, withAttachments = true))
    }
}

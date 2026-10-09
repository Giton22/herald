package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.settings.RunningSend
import kotlin.test.Test
import kotlin.test.assertEquals

class RunningSegmentsTest {

    @Test
    fun queueAndSteerShowUnlessTheSettingStopsTheTask() {
        assertEquals(listOf(RunningSend.Queue, RunningSend.Steer), runningSegments(RunningSend.Steer))
        assertEquals(listOf(RunningSend.Queue, RunningSend.Steer), runningSegments(RunningSend.Queue))
        assertEquals(listOf(RunningSend.Queue, RunningSend.StopAndSend), runningSegments(RunningSend.StopAndSend))
    }
}

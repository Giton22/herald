package dev.hermeskotlin.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusPauseTest {

    private class Controls : ReadAloudControls {
        val calls = mutableListOf<String>()
        var paused = false
        override fun pause() {
            calls += "pause"
            paused = true
        }

        override fun resume() {
            calls += "resume"
            paused = false
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private fun focus(controls: Controls) = FocusPause(controls) { controls.paused }

    @Test
    fun aMomentOfOtherAudioPausesAndGivingTheSpeakerBackResumes() {
        val controls = Controls()
        val focus = focus(controls)

        focus.lostForAMoment()
        assertTrue(controls.paused)
        assertTrue(focus.waiting)
        focus.regained()

        assertFalse(controls.paused)
        assertEquals(listOf("pause", "resume"), controls.calls)
    }

    @Test
    fun otherAudioThatStaysPausesForGoodAndGivingTheSpeakerBackDoesNotResume() {
        val controls = Controls()
        val focus = focus(controls)

        focus.lostForGood()
        focus.regained()

        assertTrue(controls.paused)
        assertEquals(listOf("pause"), controls.calls)
    }

    @Test
    fun aMomentOfOtherAudioLeavesWhatThePersonPausedPaused() {
        val controls = Controls().apply { paused = true }
        val focus = focus(controls)

        focus.lostForAMoment()
        focus.regained()

        assertTrue(controls.paused)
        assertEquals(emptyList(), controls.calls)
    }

    @Test
    fun aPersonsResumeEndsTheWaitSoALaterGiveBackDoesNothing() {
        val controls = Controls()
        val focus = focus(controls)

        focus.lostForAMoment()
        controls.resume()
        focus.resumedByPerson()
        focus.regained()

        assertFalse(controls.paused)
        assertEquals(listOf("pause", "resume"), controls.calls)
    }

    @Test
    fun theSpeakerLostForGoodAfterAMomentStopsWaiting() {
        val controls = Controls()
        val focus = focus(controls)

        focus.lostForAMoment()
        focus.lostForGood()
        focus.regained()

        assertFalse(focus.waiting)
        assertTrue(controls.paused)
    }
}

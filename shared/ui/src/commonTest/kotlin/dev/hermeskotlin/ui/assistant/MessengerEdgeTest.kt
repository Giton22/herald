package dev.hermeskotlin.ui.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessengerEdgeTest {

    @Test
    fun listeningWinsThenWorkThenTheAnswer() {
        assertEquals(EdgeMood.Listening, edgeMood(recording = true, transcribing = false, running = true, answered = true))
        assertEquals(EdgeMood.Working, edgeMood(recording = false, transcribing = true, running = false, answered = false))
        assertEquals(EdgeMood.Working, edgeMood(recording = false, transcribing = false, running = true, answered = true))
        assertEquals(EdgeMood.Answered, edgeMood(recording = false, transcribing = false, running = false, answered = true))
        assertEquals(EdgeMood.Idle, edgeMood(recording = false, transcribing = false, running = false, answered = false))
    }

    @Test
    fun theHeadIsBrightestAndTheTrailFadesBehindIt() {
        val heads = listOf(0.5f)
        assertEquals(1f, trailIntensity(0.5f, heads, tail = 0.2f))
        assertTrue(trailIntensity(0.45f, heads, 0.2f) > trailIntensity(0.35f, heads, 0.2f))
        // Ahead of the runner and past its tail, the edge is unlit.
        assertEquals(0f, trailIntensity(0.55f, heads, 0.2f))
        assertEquals(0f, trailIntensity(0.25f, heads, 0.2f))
    }

    @Test
    fun theTrailWrapsPastTheStart() {
        assertTrue(trailIntensity(0.95f, listOf(0.05f), tail = 0.2f) > 0f)
    }
}

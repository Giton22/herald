package dev.hermeskotlin.wear

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchRingbackTest {
    @Test
    fun manualCallsNeverPlayRingback() = runBlocking {
        ringCall(null) { error("Manual calls must stay silent") }
    }

    @Test
    fun shortcutRepeatsAfterThreeSecondsAndCancellationStopsIt() = runBlocking {
        val clips = Channel<Unit>(Channel.UNLIMITED)
        val ringing = launch(start = CoroutineStart.UNDISPATCHED) { ringCall("default") { clips.send(Unit) } }
        assertEquals(Unit, clips.receive())
        assertNull(withTimeoutOrNull(2_800) { clips.receive() })
        assertEquals(Unit, withTimeout(1_500) { clips.receive() })
        ringing.cancelAndJoin()
        assertNull(clips.tryReceive().getOrNull())
    }

    @Test
    fun cancellingDuringPlaybackReleasesTheClip() = runBlocking {
        var released = false
        val ringing = launch(start = CoroutineStart.UNDISPATCHED) {
            ringCall("default") {
                try { awaitCancellation() } finally { released = true }
            }
        }
        ringing.cancelAndJoin()
        assertEquals(true, released)
    }

    @Test
    fun unavailableAudioDoesNotFailTheCallOrRetryForever() = runBlocking {
        var attempts = 0
        ringCall("default") { attempts++; error("No playback") }
        assertEquals(1, attempts)
    }

    @Test
    fun onlyReadinessSpeechOrFailureStopsTheCue() {
        for (type in listOf("session.started", "session.output_transcript.delta", "error", "session.closed")) {
            assertEquals(true, endsRingback(type), type)
        }
        for (type in listOf(null, "session.input_transcript.delta", "session.delegation.created")) {
            assertEquals(false, endsRingback(type), type)
        }
    }
}

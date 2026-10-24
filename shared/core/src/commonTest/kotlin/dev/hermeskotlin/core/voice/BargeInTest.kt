package dev.hermeskotlin.core.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BargeInTest {

    /** A [BargeIn] fed 20 ms frames, the clock carried from one stretch to the next. */
    private class Mic(val bargeIn: BargeIn = BargeIn()) {
        var now = 0L

        /** Feeds [ms] of [level]; when it tripped or finished in that stretch, and which, or null. */
        fun feed(level: Float, ms: Long, playing: Boolean = false): Pair<Long, BargeIn.Step>? {
            val until = now + ms
            while (now < until) {
                now += 20
                val step = bargeIn.onFrame(level, now, playing)
                if (step == BargeIn.Step.Tripped || step == BargeIn.Step.Done) return now to step
            }
            return null
        }
    }

    @Test
    fun speechThatHoldsCutsIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        val (at, step) = assertNotNull(mic.feed(0.2f, 1_000))
        assertEquals(BargeIn.Step.Tripped, step)
        // Most of 300 ms of it, not the first frame.
        assertTrue(at - 1_000 in 240L..340L, "tripped ${at - 1_000} ms in")
    }

    @Test
    fun aBlipDoesNotCutIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        assertNull(mic.feed(0.3f, 120))
        assertNull(mic.feed(0.01f, 2_000))
    }

    @Test
    fun theVoicesBleedAloneDoesNotCutIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        // Over the quiet room's bar, under the one while the voice plays.
        assertNull(mic.feed(0.07f, 5_000, playing = true))
        assertNotNull(mic.feed(0.2f, 1_000, playing = true))
    }

    @Test
    fun theVoicesFirstMomentDoesNotCutIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        // The voice starting is loud for a moment, inside the grace.
        assertNull(mic.feed(0.2f, 400, playing = true))
        assertNull(mic.feed(0.02f, 1_000, playing = true))
    }

    @Test
    fun theFloorIsNotMeasuredOverTheVoice() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        assertNull(mic.feed(0.08f, 5_000, playing = true))
        // Had the bleed lifted the floor, quiet speech after the voice stopped would be out of reach.
        assertNull(mic.feed(0.01f, 300))
        assertNotNull(mic.feed(0.06f, 1_000))
    }

    @Test
    fun speechStaysWithinReachInALoudRoom() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.1f, 1_000))
        assertEquals(BargeIn.TRIGGER_CEILING_LEVEL, bargeIn.trigger(playing = true))
        // The voice is quiet under the room's noise, so the bar stays at the cap.
        assertNull(mic.feed(0.1f, 1_000, playing = true))
        assertNotNull(mic.feed(0.3f, 1_000, playing = true))
    }

    /** A loudspeaker with no echo cancelling: its own voice comes back loud, and mustn't pass for someone talking. */
    @Test
    fun aLoudEchoRaisesTheBarAboveItself() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        // Syllables well over the stock bar, the whole reply long.
        repeat(20) {
            assertNull(mic.feed(0.35f, 200, playing = true))
            assertNull(mic.feed(0.15f, 200, playing = true))
        }
        assertTrue(bargeIn.trigger(playing = true) > 0.35f)
        // Someone talking clearly over it still cuts in.
        assertNotNull(mic.feed(0.9f, 1_000, playing = true))
    }

    /** Turned right up with no echo cancelling to speak of: syllables louder than speech, the whole reply long. */
    @Test
    fun aLoudSpeakerDoesNotCutInOnItself() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        repeat(20) {
            assertNull(mic.feed(0.6f, 200, playing = true))
            assertNull(mic.feed(0.3f, 200, playing = true))
        }
    }

    /** It starts softly and gets loud once the grace is over, past the echo it learnt first. */
    @Test
    fun aVoiceThatGetsLouderDoesNotCutInOnItself() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        assertNull(mic.feed(0.15f, 600, playing = true))
        repeat(15) {
            assertNull(mic.feed(0.5f, 200, playing = true))
            assertNull(mic.feed(0.25f, 200, playing = true))
        }
        // Someone talking over it, and staying there, still cuts in.
        assertNotNull(mic.feed(0.9f, 1_000, playing = true))
    }

    /** However loud the speaker, the bar stays where someone talking can reach it. */
    @Test
    fun aVeryLoudEchoLeavesTheBarInReach() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        repeat(20) {
            assertNull(mic.feed(0.8f, 200, playing = true))
            assertNull(mic.feed(0.3f, 200, playing = true))
        }
        assertEquals(BargeIn.ECHO_BAR_CEILING_LEVEL, bargeIn.trigger(playing = true))
        assertNotNull(mic.feed(0.5f, 1_000, playing = true))
    }

    /** Someone talking a little under the bar isn't learnt as the echo, so it doesn't climb away as they try. */
    @Test
    fun talkingUnderTheBarDoesNotRaiseIt() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        repeat(5) {
            assertNull(mic.feed(0.15f, 200, playing = true))
            assertNull(mic.feed(0.07f, 200, playing = true))
        }
        val bar = bargeIn.trigger(playing = true)
        assertNull(mic.feed(bar * 0.9f, 2_000, playing = true))
        assertEquals(bar, bargeIn.trigger(playing = true))
        // Then a little louder, and it cuts in.
        assertNotNull(mic.feed(bar * 1.15f, 1_000, playing = true))
    }

    /** A listen that starts over the voice never heard the room quiet; its hum, once the voice holds, isn't someone. */
    @Test
    fun aRoomsHumOnceTheVoiceHoldsIsNotSomeone() {
        val bargeIn = BargeIn()
        val mic = Mic(bargeIn)
        assertEquals(BargeIn.Step.Tripped, assertNotNull(mic.feed(0.6f, 2_000, playing = true)).second)
        assertEquals(BargeIn.Step.Done, assertNotNull(mic.feed(0.045f, 2_000)).second)
        assertFalse(bargeIn.spoke)
    }

    /** The room's floor carries over from the chat's earlier listen, so a louder room's hum isn't someone either. */
    @Test
    fun theRoomsFloorCarriesOverToTheNextListen() {
        val first = BargeIn()
        val quiet = Mic(first)
        assertNull(quiet.feed(0.06f, 1_000))
        assertTrue(first.roomFloor > 0.05f)

        val next = BargeIn(roomFloor = first.roomFloor)
        val mic = Mic(next)
        assertEquals(BargeIn.Step.Tripped, assertNotNull(mic.feed(0.6f, 2_000, playing = true)).second)
        assertEquals(BargeIn.Step.Done, assertNotNull(mic.feed(0.09f, 2_000)).second)
        assertFalse(next.spoke)
    }

    /** Echo can't come before the voice: someone already talking when it starts isn't learnt as its echo. */
    @Test
    fun someoneAlreadyTalkingAsTheVoiceStartsStillCutsIn() {
        val mic = Mic()
        assertNull(mic.feed(0.01f, 1_000))
        assertNull(mic.feed(0.3f, 100))
        val (at, _) = assertNotNull(mic.feed(0.3f, 4_000, playing = true))
        // Once the grace is over and the speech has held.
        assertTrue(at - 1_100 <= 1_000, "tripped ${at - 1_100} ms in")
    }

    @Test
    fun aLowerMultiplierCutsInSooner() {
        val sensitive = Mic(BargeIn(BargeInSettings(multiplier = 1.5)))
        assertNull(sensitive.feed(0.01f, 1_000))
        assertNull(sensitive.feed(0.02f, 1_000, playing = true))
        assertNotNull(sensitive.feed(0.04f, 2_000, playing = true))

        val stock = Mic(BargeIn(BargeInSettings(multiplier = BargeIn.DEFAULT_MULTIPLIER)))
        assertNull(stock.feed(0.01f, 1_000))
        assertNull(stock.feed(0.02f, 1_000, playing = true))
        assertNull(stock.feed(0.04f, 2_000, playing = true))
    }

    /** Set for a quiet headset microphone, it still mustn't cut in on the voice's own echo. */
    @Test
    fun aLowerMultiplierStaysAboveTheVoicesEcho() {
        for (multiplier in listOf(1.5, 1.0, 0.5)) {
            val mic = Mic(BargeIn(BargeInSettings(multiplier = multiplier)))
            assertNull(mic.feed(0.01f, 1_000))
            repeat(10) {
                assertNull(mic.feed(0.2f, 200, playing = true), "cut in on its echo at $multiplier")
                assertNull(mic.feed(0.1f, 200, playing = true), "cut in on its echo at $multiplier")
            }
        }
    }

    /** Its own echo stops when the voice holds: nobody goes on, and that's known well before a whole pause. */
    @Test
    fun nobodyGoingOnOnceTheVoiceHoldsEndsItSoon() {
        val bargeIn = BargeIn(silenceMs = 1_250)
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        val (trippedAt, _) = assertNotNull(mic.feed(0.2f, 1_000))
        // The echo dies away as the speaker's buffer and the room empty.
        assertNull(mic.feed(0.15f, 200))
        val (at, step) = assertNotNull(mic.feed(0.01f, 3_000))
        assertEquals(BargeIn.Step.Done, step)
        assertFalse(bargeIn.spoke)
        assertTrue(at - trippedAt <= BargeIn.NOBODY_AFTER_MS + 20, "done ${at - trippedAt} ms after the trip")
    }

    @Test
    fun someoneGoingOnOnceTheVoiceHoldsIsHeardToTheirPause() {
        val bargeIn = BargeIn(silenceMs = 1_000)
        val mic = Mic(bargeIn)
        assertNull(mic.feed(0.01f, 1_000))
        assertNotNull(mic.feed(0.2f, 1_000))
        assertNull(mic.feed(0.2f, 1_500))
        assertTrue(bargeIn.spoke)
        assertEquals(BargeIn.Step.Done, assertNotNull(mic.feed(0.01f, 3_000)).second)
    }

    @Test
    fun theUtteranceEndsOnAPause() {
        val mic = Mic(BargeIn(silenceMs = 1_000))
        assertNull(mic.feed(0.01f, 1_000))
        assertNotNull(mic.feed(0.2f, 400))
        // Words with short gaps between them carry on.
        assertNull(mic.feed(0.2f, 1_500))
        assertNull(mic.feed(0.01f, 500))
        assertNull(mic.feed(0.2f, 500))
        val trippedAt = mic.now
        val (at, step) = assertNotNull(mic.feed(0.01f, 3_000))
        assertEquals(BargeIn.Step.Done, step)
        // A second from the first quiet frame.
        assertEquals(1_020L, at - trippedAt)
    }

    @Test
    fun aRatioMatchesPythons() {
        fun ratio(a: String, b: String) = sequenceRatio(a.toList(), b.toList())
        assertClose(0.7857142857142857, ratio("hello world", "hello there world"))
        assertClose(0.8636363636363636, ratio("the quick brown fox", "the quick brown fox jumps"))
        assertClose(0.0, ratio("abc", ""))
        assertClose(1.0, ratio("", ""))
        // Past 200 characters, the commonest ones can't start a match (difflib's autojunk).
        assertClose(0.10223642172523961, ratio("aaab the weather", "a".repeat(150) + "b".repeat(120) + " the weather today is sunny"))
    }

    @Test
    fun itsOwnVoiceIsAnEcho() {
        val reply = "The build finished in four minutes and every test passed. Do you want me to deploy it now?"
        assertTrue(isTtsEcho("The build finished in four minutes", reply))
        assertTrue(isTtsEcho("every test passed do you want", reply))
        assertTrue(isTtsEcho("the build finished in 4 minutes and every test passed. do you want me to deploy it now", reply))
    }

    /** Every stretch of a long reply is still looked at: the echo is found wherever it comes, and only there. */
    @Test
    fun anEchoIsFoundAnywhereInALongReply() {
        val filler = "Then the job went on to the next stage and wrote its results to the shared drive. "
        val reply = filler.repeat(60) + "The backup finished overnight and all three drives check out. " + filler.repeat(20)
        assertTrue(isTtsEcho("the backup finished overnight and all three drives", reply))
        assertFalse(isTtsEcho("Wait, check the staging logs first", reply))
    }

    @Test
    fun someoneCuttingInIsNotAnEcho() {
        val reply = "The build finished in four minutes and every test passed. Do you want me to deploy it now?"
        assertFalse(isTtsEcho("Wait, check the staging logs first", reply))
        assertFalse(isTtsEcho("yes", reply))
        assertFalse(isTtsEcho("no", reply))
        assertFalse(isTtsEcho("anything", ""))
    }

    private fun assertClose(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-12, "expected $expected, was $actual")
}

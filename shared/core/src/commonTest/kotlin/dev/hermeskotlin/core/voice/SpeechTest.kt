package dev.hermeskotlin.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechTest {

    @Test
    fun repliesSoundLikeProseNotMarkup() {
        val reply = """
            ## Result
            Run `ls -la` in the [docs](https://example.com/docs):
            ```
            ls -la
            ```
            - **One** thing 🎉
            | Model | Price |
            |-------|-------|
            | a | b |
            See MEDIA:/tmp/report.pdf. Done.
        """.trimIndent()
        assertEquals("Result Run ls -la in the docs. One thing See. Done.", speakableText(reply))
    }

    @Test
    fun theFirstChunkIsShortSoSpeechStartsSoon() {
        val text = "First sentence here. " + "Another one follows with more words in it. ".repeat(12)
        val chunks = speechChunks(text, maxChars = 200, firstChars = 60)
        assertEquals("First sentence here.", chunks.first())
        assertTrue(chunks.drop(1).all { it.length <= 200 })
        assertEquals(text.trim(), chunks.joinToString(" "))
    }

    /** Feeds [levels] as 20 ms frames; the time the recording would end, or null if it wouldn't. */
    private fun endsAt(levels: List<Float>, activity: VoiceActivity = VoiceActivity()): Long? {
        val detector = EndOfSpeech(activity)
        levels.forEachIndexed { i, level -> if (detector.onFrame(level, (i + 1) * 20L)) return (i + 1) * 20L }
        return null
    }

    private fun frames(level: Float, ms: Long) = List((ms / 20).toInt()) { level }

    @Test
    fun aPauseAfterSpeechEndsTheRecordingInAQuietRoom() {
        val end = endsAt(frames(0.01f, 1_000) + frames(0.4f, 1_500) + frames(0.01f, 3_000))
        // Speech stops at 2.5 s; the first quiet frame (2.52 s) starts the 1.25 s pause.
        assertEquals(3_780L, end)
    }

    @Test
    fun aNoisyRoomStillFindsTheEndOfSpeech() {
        // Background hum above Desktop's fixed level would never count as quiet without the noise floor.
        val noisy = frames(0.12f, 1_000) + frames(0.6f, 1_500) + frames(0.13f, 3_000)
        val end = endsAt(noisy)
        assertTrue(end != null && end in 3_700L..4_000L, "ended at $end")
    }

    @Test
    fun hummingAlongDoesNotCountAsSpeech() {
        // Steady noise alone never starts a turn; the idle limit ends the recording without speech.
        val detector = EndOfSpeech(VoiceActivity(idleMs = 2_000))
        var ended = 0L
        for (i in 1..200) {
            if (detector.onFrame(0.15f, i * 20L)) {
                ended = i * 20L
                break
            }
        }
        assertEquals(2_000L, ended)
        assertFalse(detector.heardSpeech)
    }

    @Test
    fun onlyAWholeStopUtteranceEndsTheChat() {
        assertTrue(isVoiceStopCommand("Stop."))
        assertTrue(isVoiceStopCommand("Hey Hermes, never mind!"))
        assertTrue(isVoiceStopCommand("okay goodbye"))
        assertFalse(isVoiceStopCommand("stop the docker container"))
        assertFalse(isVoiceStopCommand("how do I cancel a job"))
    }
}

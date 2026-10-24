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
    fun emojiRunsAreSilenceAndOtherSymbolsStay() {
        assertEquals("Done ok", speakableText("Done 👍🏽✅ ok"))
        assertEquals("Family time", speakableText("Family 👨‍👩‍👧 time"))
        assertEquals("π ≈ 3.14 €5", speakableText("π ≈ 3.14 €5"))
    }

    @Test
    fun stopIsHeardThroughAnyPunctuation() {
        assertTrue(isVoiceStopCommand("Stop!"))
        assertTrue(isVoiceStopCommand("„never mind…“"))
        assertFalse(isVoiceStopCommand("Stop the container."))
    }

    @Test
    fun readAloudLeavesOutWhatTheReplyShowsAsFilesAndPictures() {
        val reply = """
            Here's the chart: ![chart](/opt/data/out/chart.png)
            ::preview{file="/opt/data/out/report.html"}
            The report is at file:///opt/data/out/report.pdf and MEDIA:/opt/data/out/report.pdf as well.
        """.trimIndent()
        assertEquals("Here's the chart. The report is at and as well.", readAloudText(reply))
    }

    @Test
    fun aReplyOfOnlyCodeOrPicturesHasNothingToReadAloud() {
        assertEquals("", readAloudText("```kotlin\nfun main() = println(\"hi\")\n```"))
        assertEquals("", readAloudText("![plot](/tmp/plot.png)"))
        assertEquals("Done.", readAloudText("Done.\n```\nls\n```"))
        // Punctuation alone gives the gateway no words to say.
        assertEquals("", readAloudText("…"))
        assertEquals("", readAloudText("?! 😅"))
        assertEquals("3.", readAloudText("3."))
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
    fun anUnboostedPhoneMicStillCountsSoftSpeech() {
        // A phone's raw mic: speech near 0.05 over a 0.005 room never reached Desktop's fixed 0.075.
        val end = endsAt(frames(0.005f, 1_000) + frames(0.05f, 1_500) + frames(0.006f, 3_000))
        assertEquals(3_780L, end)
    }

    @Test
    fun aNoisyRoomStillFindsTheEndOfSpeech() {
        // Background hum above Desktop's fixed level would never count as quiet without the noise floor.
        val noisy = frames(0.12f, 1_000) + frames(0.6f, 1_500) + frames(0.13f, 3_000)
        val end = endsAt(noisy)
        assertTrue(end != null && end in 3_700L..4_000L, "ended at $end")
    }

    /** #185: road noise or a fan pokes above the quiet bar now and then; each blip used to start the pause over. */
    @Test
    fun briefNoiseAboveQuietDoesntHoldTheRecordingOpen() {
        // A noisy room (0.1); after speech, 100 ms blips half way to speech every 400 ms, never quiet for 1.25 s.
        val blips = List(15) { frames(0.1f, 300) + frames(0.2f, 100) }.flatten()
        val end = endsAt(frames(0.1f, 1_000) + frames(0.6f, 1_500) + blips)
        // Speech stops at 2.5 s: the pause runs on through the blips.
        assertTrue(end != null && end in 3_700L..4_000L, "ended at $end")
    }

    @Test
    fun knocksUpToSpeechDontHoldItOpenEither() {
        // A bump or a click as loud as speech, 40 ms, every 340 ms: shorter than any syllable.
        val knocks = List(15) { frames(0.1f, 300) + frames(0.6f, 40) }.flatten()
        val end = endsAt(frames(0.1f, 1_000) + frames(0.6f, 1_500) + knocks)
        assertTrue(end != null && end in 3_700L..4_000L, "ended at $end")
    }

    @Test
    fun softTalkJustAboveQuietStillHoldsIt() {
        // Trailing off: half a second of soft words, short of the speech bar but above quiet.
        val end = endsAt(frames(0.1f, 1_000) + frames(0.6f, 1_500) + frames(0.38f, 500) + frames(0.1f, 3_000))
        // The pause starts about when the soft words end (3.0 s), not at the end of the loud ones.
        assertTrue(end != null && end in 4_100L..4_300L, "ended at $end")
    }

    @Test
    fun clippedSyllablesWithGapsStillHoldIt() {
        // "It's a, uh, to the…": 100 ms syllables, each followed by 100 ms back at the noise, for 2.4 s.
        val syllables = List(12) { frames(0.6f, 100) + frames(0.1f, 100) }.flatten()
        val end = endsAt(frames(0.1f, 1_000) + syllables + frames(0.1f, 3_000))
        // The last syllable ends at 3.3 s; the recording runs until the whole pause after it.
        assertTrue(end != null && end in 4_500L..4_700L, "ended at $end")
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

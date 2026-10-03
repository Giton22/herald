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

    @Test
    fun onlyAWholeStopUtteranceEndsTheChat() {
        assertTrue(isVoiceStopCommand("Stop."))
        assertTrue(isVoiceStopCommand("Hey Hermes, never mind!"))
        assertTrue(isVoiceStopCommand("okay goodbye"))
        assertFalse(isVoiceStopCommand("stop the docker container"))
        assertFalse(isVoiceStopCommand("how do I cancel a job"))
    }
}

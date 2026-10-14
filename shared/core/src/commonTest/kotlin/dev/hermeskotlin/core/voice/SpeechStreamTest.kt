package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.gateway.GatewayUrl
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechStreamTest {

    @Test
    fun theGatewaysFramesAreRead() {
        assertEquals(SpeechStreamEvent.Start(sampleRate = 22_050, channels = 1), parseSpeechFrame("""{"type":"start","sample_rate":22050,"channels":1}"""))
        assertEquals(SpeechStreamEvent.Start(sampleRate = 24_000), parseSpeechFrame("""{"type":"start"}"""))
        assertEquals(SpeechStreamEvent.End, parseSpeechFrame("""{"type":"end"}"""))
        assertEquals(SpeechStreamEvent.Fallback, parseSpeechFrame("""{"type":"fallback"}"""))
        assertNull(parseSpeechFrame("""{"type":"later"}"""))
        assertNull(parseSpeechFrame("not json"))
    }

    @Test
    fun codeAndAttachedFilesArentVoiced() {
        val reply = "Done. Run this:\n```bash\nls -la\n```\nMEDIA:/tmp/out.png\nThat's all."

        assertEquals("Done. Run this:\n\nThat's all.", speakableSoFar(reply))
    }

    @Test
    fun aFileAmongTheWordsOrOnAWindowsLineIsntVoiced() {
        assertEquals("Here is the chart: ", speakableSoFar("Here is the chart: MEDIA:/tmp/out.png", complete = true))
        assertEquals("See  and more.", speakableSoFar("See MEDIA:/tmp/a.png and more."))
        assertEquals("One.\r\nTwo.", speakableSoFar("One.\r\nMEDIA:/tmp/a.png\r\nTwo."))
    }

    @Test
    fun whatWasHeldBackIsVoicedOnceTheReplyIsComplete() {
        assertEquals("Try running `ls", speakableSoFar("Try running `ls`"))
        assertEquals("Try running `ls`", speakableSoFar("Try running `ls`", complete = true))
        assertEquals("Thanks,\n", speakableSoFar("Thanks,\nME"))
        assertEquals("Thanks,\nME", speakableSoFar("Thanks,\nME", complete = true))
        // Code is still code.
        assertEquals("Here:\n", speakableSoFar("Here:\n```py\nprint(", complete = true))
    }

    @Test
    fun anOpenCodeBlockHoldsBackWhatFollows() {
        assertEquals("Here:\n", speakableSoFar("Here:\n```py\nprint("))
    }

    /** Fed a character at a time, what can be voiced only ever grows, so each new part can be sent as it comes. */
    @Test
    fun whatCanBeVoicedOnlyGrowsAsTheReplyIsWritten() {
        val reply = "Sure. `x` is set.\n```kotlin\nval a = 1\n```\nMEDIA:/tmp/a.png\r\nMore text, and `` a pair ``.\nMEDI is not a file, " +
            "nor is ME. Chart: MEDIA:/tmp/c.png, done. The end `ok`"
        var before = ""
        for (i in 0..reply.length) {
            val now = speakableSoFar(reply.substring(0, i))
            assertTrue(now.startsWith(before), "at $i: “$now” doesn't continue “$before”")
            before = now
        }
        val complete = speakableSoFar(reply, complete = true)
        assertTrue(complete.startsWith(before), "“$complete” doesn't continue “$before”")
        assertEquals("Sure. `x` is set.\n\nMore text, and `` a pair ``.\nMEDI is not a file, nor is ME. Chart: , done. The end `ok`", complete)
    }

    /** No-break spaces, long backtick runs and an indented last line keep it growing too. */
    @Test
    fun whatCanBeVoicedOnlyGrowsWithOddSpacingAndBackticks() {
        for (reply in listOf("see MEDIA:a v1.2 next", "a MEDIA:/x.png b", "```a````` b```c", "Hi.\n  MEDIA:/a.png\nBye.")) {
            var before = ""
            for (i in 0..reply.length) {
                val now = speakableSoFar(reply.substring(0, i))
                assertTrue(now.startsWith(before), "“$reply” at $i: “$now” doesn't continue “$before”")
                before = now
            }
        }
        // A file after a no-break space is still a file.
        assertEquals("a  b", speakableSoFar("a MEDIA:/x.png b"))
    }

    @Test
    fun aWavHeaderDescribesThePcm() {
        val file = wav(ByteArray(8), 24_000)

        assertEquals(44 + 8, file.size)
        assertEquals("RIFF", file.decodeToString(0, 4))
        assertEquals("WAVEfmt ", file.decodeToString(8, 16))
        // The sample rate, little-endian, at byte 24.
        assertEquals(24_000, (file[24].toInt() and 0xff) or ((file[25].toInt() and 0xff) shl 8) or ((file[26].toInt() and 0xff) shl 16))
    }

    /** OkHttp fails a socket the gateway closed with its own cancellation (#183); it isn't the caller's. */
    private fun closedByTheGateway() = CancellationException("WebSocket session closed with code NORMAL.")

    @Test
    fun aSocketTheGatewayClosedIsQuietToSendTo() = runTest {
        val outgoing = Channel<Frame>(Channel.UNLIMITED).apply { cancel(closedByTheGateway()) }
        var socketClosed = false
        val stream = KtorSpeechStream(Channel(), outgoing) { socketClosed = true }

        stream.append("More.")
        stream.finish()
        stream.close()

        assertTrue(socketClosed)
    }

    @Test
    fun aSocketTheGatewayClosedEndsTheEventsWithWhatWasSaid() = runTest {
        val incoming = Channel<Frame>()
        val stream = KtorSpeechStream(incoming, Channel(Channel.UNLIMITED)) {}
        launch {
            incoming.send(Frame.Text("""{"type":"start","sample_rate":24000}"""))
            incoming.send(Frame.Binary(true, byteArrayOf(1, 0)))
            incoming.cancel(closedByTheGateway())
        }

        val events = stream.events.toList()

        assertEquals(SpeechStreamEvent.Start(sampleRate = 24_000), events.first())
        assertEquals(2, events.size)
    }

    @Test
    fun theCallersOwnCancellationStillGoesUp() = runTest {
        // Nobody takes the frame: the send waits until the caller is cancelled.
        val stream = KtorSpeechStream(Channel(), Channel()) {}
        var after = false
        val sending = launch {
            stream.append("Hello.")
            after = true
        }
        testScheduler.runCurrent()

        sending.cancel()
        sending.join()

        assertFalse(after)
    }

    @Test
    fun aCollectorCancelledWhileWaitingIsCancelledNotDone() = runTest {
        val stream = KtorSpeechStream(Channel(), Channel()) {}
        var done = false
        val listening = launch {
            stream.events.toList()
            done = true
        }
        testScheduler.runCurrent()

        listening.cancel()
        listening.join()

        assertFalse(done)
    }

    @Test
    fun aCollectorThatStopsEarlyIsntSwallowed() = runTest {
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        incoming.send(Frame.Text("""{"type":"start","sample_rate":24000}"""))
        incoming.send(Frame.Binary(true, byteArrayOf(1, 0)))
        val stream = KtorSpeechStream(incoming, Channel(Channel.UNLIMITED)) {}

        // Taking one aborts the flow from downstream; swallowing that would break the flow's contract.
        assertEquals(SpeechStreamEvent.Start(sampleRate = 24_000), stream.events.first())
    }

    @Test
    fun siblingSocketsSitBesideTheGatewaySocket() {
        assertEquals("wss://h.example.com/p/api/audio/speak-stream", GatewayUrl.parse("https://h.example.com/p/").siblingSocketUrl("api/audio/speak-stream"))
        assertEquals("ws://host:9119/api/ws", GatewayUrl.parse("host:9119").webSocketUrl)
    }
}

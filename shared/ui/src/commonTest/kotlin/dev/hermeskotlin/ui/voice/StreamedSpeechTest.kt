package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpeechStream
import dev.hermeskotlin.core.voice.SpeechStreamEvent
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamedSpeechTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** The gateway's socket: creates the chat and takes prompts; the test plays the turn's events. */
    private class Socket : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        val submits = MutableStateFlow<List<JsonObject>>(emptyList())
        /** Prompts are taken but never answered, as on a gateway that's slow to. */
        var holdPrompts = false
        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            if (holdPrompts && message["method"]?.jsonPrimitive?.contentOrNull == "prompt.submit") {
                submits.update { it + message }
                return
            }
            val result = when (message["method"]?.jsonPrimitive?.contentOrNull) {
                "session.create" -> """{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}"""
                "prompt.submit" -> """{"status":"streaming"}""".also { submits.update { it + message } }
                else -> "{}"
            }
            inbound.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) = Unit

        fun event(type: String, payload: String = "{}") {
            inbound.trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt9","payload":$payload}}""")
        }
    }

    /** Hears one request (or [requests] of them), then listens for good. */
    private class OneRequest(val requests: Int = 1) : VoiceRecorder {
        var recordings = 0
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            if (recordings++ >= requests) awaitCancellation()
            onSpeech()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heardSpeech = true)
        }

        override fun finish() = Unit
    }

    /** Hears one request, then nobody for [quiet] listens, then listens for good. */
    private class QuietAfterOne(val quiet: Int) : VoiceRecorder {
        var recordings = 0
        val waiting = CompletableDeferred<Unit>()
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            if (recordings > quiet) {
                waiting.complete(Unit)
                awaitCancellation()
            }
            val speech = recordings++ == 0
            if (speech) onSpeech()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heardSpeech = speech)
        }

        override fun finish() = Unit
    }

    private class Player(val fails: Boolean = false) : SpeechPlayer {
        val clips = mutableListOf<SpokenAudio>()
        val streamed = MutableStateFlow<List<ByteArray>>(emptyList())
        var rate = 0
        var channels = 0
        var pcm: ReceiveChannel<ByteArray>? = null
        override suspend fun play(audio: SpokenAudio) {
            clips += audio
        }

        override suspend fun playPcm(sampleRate: Int, channels: Int, chunks: ReceiveChannel<ByteArray>, onSound: (Boolean) -> Unit) {
            rate = sampleRate
            this.channels = channels
            pcm = chunks
            if (fails) error("No audio track.")
            onSound(true)
            for (chunk in chunks) streamed.update { it + chunk }
            onSound(false)
        }
    }

    /** [closedByTheGateway]: like OkHttp's socket once the gateway closed it, closing throws a cancellation of its own. */
    private class Stream(val closedByTheGateway: Boolean = false) : SpeechStream {
        val gateway = Channel<SpeechStreamEvent>(Channel.UNLIMITED)
        val appended = MutableStateFlow("")
        var finished = false
        var closed = false
        // Like the gateway's socket, the answers end with End or Fallback.
        override val events: Flow<SpeechStreamEvent> = gateway.receiveAsFlow()
            .transformWhile { emit(it); it != SpeechStreamEvent.End && it != SpeechStreamEvent.Fallback }

        override suspend fun append(text: String) = appended.update { it + text }

        override suspend fun finish() {
            finished = true
        }

        override suspend fun close() {
            closed = true
            gateway.close()
            if (closedByTheGateway) throw CancellationException("WebSocket session closed with code NORMAL.")
        }
    }

    private class Setup(val socket: Socket, val voice: VoiceController, val player: Player, val spoken: MutableList<String>)

    /** A chained voice chat on a chat whose gateway voices replies through [stream], or can't when it's null. */
    private suspend fun TestScope.chat(stream: Stream?, player: Player = Player()): Setup =
        chat(player) { stream }

    private suspend fun TestScope.chat(
        player: Player = Player(),
        recorder: VoiceRecorder = OneRequest(),
        holdPrompts: Boolean = false,
        /** The speech provider refuses every reply, as one over its quota does. */
        speakFails: Boolean = false,
        streams: () -> SpeechStream?,
    ): Setup {
        val spoken = mutableListOf<String>()
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/transcribe" -> respond("""{"ok":true,"transcript":"How was your day?"}""", HttpStatusCode.OK, json)
                    "/api/audio/speak" -> if (speakFails) {
                        respond("""{"detail":"TTS chunk 1 failed: quota exceeded"}""", HttpStatusCode.BadRequest, json)
                    } else {
                        spoken += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["text"]!!.jsonPrimitive.content
                        respond("""{"ok":true,"data_url":"data:audio/mpeg;base64,AAAA","mime_type":"audio/mpeg"}""", HttpStatusCode.OK, json)
                    }
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val socket = Socket().also { it.holdPrompts = holdPrompts }
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val voice = VoiceController(AudioApi(client), recorder, player, backgroundScope, backgroundScope, speechStreams = { _, _ -> streams() })
        voice.startChat(session, gateway, "rin")
        socket.submits.first { it.isNotEmpty() }
        return Setup(socket, voice, player, spoken)
    }

    @Test
    fun aReplyIsVoicedWhileItIsStillBeingWritten() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was good. "}""")
        // The first sentence reaches the gateway while the reply is still being written.
        stream.appended.first { it == "It was good. " }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.player.streamed.first { it.size == 1 }
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        assertEquals(24_000, setup.player.rate)

        // Code isn't read out, nor an attached file.
        setup.socket.event("message.delta", """{"text":"Here:\n```kotlin\nval x = 1\n```\nMEDIA:/tmp/day.png\nAnd you?"}""")
        setup.socket.event("message.complete", """{"text":"It was good. Here:\n```kotlin\nval x = 1\n```\nMEDIA:/tmp/day.png\nAnd you?","status":"complete"}""")
        stream.appended.first { it.endsWith("And you?") }
        assertEquals("It was good. Here:\n\nAnd you?", stream.appended.value)
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(3, 0)))
        stream.gateway.send(SpeechStreamEvent.End)

        setup.voice.chat.first { it.phase == VoicePhase.Listening }
        assertTrue(stream.finished)
        assertEquals(2, setup.player.streamed.value.size)
        // Voiced once, as it streamed: never again from the finished reply.
        assertTrue(setup.spoken.isEmpty())
        assertTrue(setup.player.clips.isEmpty())
        assertTrue(stream.closed)
        setup.voice.stopChat()
    }

    @Test
    fun aGatewayThatMadeNoAudioReadsTheFinishedReply() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"All good."}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Fallback)
        setup.socket.event("message.complete", """{"text":"All good.","status":"complete"}""")

        setup.voice.chat.first { it.phase == VoicePhase.Listening && setup.spoken.isNotEmpty() }
        assertEquals(listOf("All good."), setup.spoken)
        assertEquals(1, setup.player.clips.size)
        setup.voice.stopChat()
    }

    @Test
    fun withoutTheStreamTheFinishedReplyIsReadOut() = runTest {
        val setup = chat(stream = null)

        setup.socket.event("message.start")
        setup.socket.event("message.complete", """{"text":"Fine, thanks.","status":"complete"}""")

        setup.voice.chat.first { it.phase == VoicePhase.Listening && setup.spoken.isNotEmpty() }
        assertEquals(listOf("Fine, thanks."), setup.spoken)
        setup.voice.stopChat()
    }

    @Test
    fun skippingStopsTheVoiceAndDoesntReadTheReplyAgain() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"A long story begins. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }

        setup.voice.skipSpeech()
        setup.socket.event("message.complete", """{"text":"A long story begins. It goes on.","status":"complete"}""")

        setup.voice.chat.first { it.phase == VoicePhase.Listening }
        assertTrue(stream.closed)
        assertTrue(setup.spoken.isEmpty())
        assertFalse(setup.player.clips.isNotEmpty())
        setup.voice.stopChat()
    }

    /** Skip says "tap skip to talk again": with the agent still at work, that's now, not once it's done. */
    @Test
    fun skippingWhileTheAgentStillWorksListensAgainAtOnce() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"Let me look into it. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000, channels = 2))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        // Played as the gateway sent it.
        assertEquals(2, setup.player.channels)

        // The agent goes on with its tools; the turn hasn't ended.
        setup.voice.skipSpeech()

        setup.voice.chat.first { it.phase == VoicePhase.Listening }
        assertTrue(stream.closed)
        assertTrue(setup.spoken.isEmpty())
        setup.voice.stopChat()
    }

    @Test
    fun aVoiceThatBrokeOffReadsTheFinishedReply() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was good. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0)))
        setup.player.streamed.first { it.size == 1 }
        // The socket drops after the first sentence: the rest would never be heard.
        stream.gateway.close()
        setup.socket.event("message.delta", """{"text":"And then some."}""")
        setup.socket.event("message.complete","""{"text":"It was good. And then some.","status":"complete"}""")

        setup.voice.chat.first { it.phase == VoicePhase.Listening && setup.spoken.isNotEmpty() }
        assertEquals(listOf("It was good. And then some."), setup.spoken)
        setup.voice.stopChat()
    }

    /** #184's case: the reply's voice failed, then nobody spoke for a while. The chat listens on. */
    @Test
    fun aVoiceThatFailedThenAQuietRoomKeepsTheChat() = runTest {
        val recorder = QuietAfterOne(quiet = 4)
        val setup = chat(recorder = recorder, speakFails = true) { null }

        setup.socket.event("message.start")
        setup.socket.event("message.complete", """{"text":"Fine.","status":"complete"}""")

        recorder.waiting.await()
        assertEquals(VoicePhase.Listening, setup.voice.chat.value.phase)
        // Why the reply wasn't heard stays on show.
        assertTrue(setup.voice.chat.value.error != null)
        setup.voice.stopChat()
    }

    /** #183: the gateway closed the speech socket, and closing it threw OkHttp's cancellation: the chat goes on. */
    @Test
    fun aSocketTheGatewayClosedDoesntEndTheChat() = runTest {
        val stream = Stream(closedByTheGateway = true)
        val setup = chat(recorder = OneRequest(requests = 2)) { stream }

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"Fine. "}""")
        stream.appended.first { it.isNotEmpty() }
        // Its events end the same way, which is the voice breaking off, not a skip.
        stream.gateway.close(CancellationException("WebSocket session closed with code NORMAL."))
        setup.socket.event("message.delta", """{"text":"And you?"}""")
        setup.socket.event("message.complete", """{"text":"Fine. And you?","status":"complete"}""")

        // The finished reply is read out instead, and the next turn goes out.
        setup.socket.submits.first { it.size == 2 }
        assertEquals(listOf("Fine. And you?"), setup.spoken)
        assertTrue(stream.closed)
        setup.voice.stopChat()
    }

    /** Events that end with a stray cancellation are the voice breaking off, not a skip: the reply is read out. */
    @Test
    fun eventsEndedByTheSocketsOwnCancellationReadTheReply() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"Fine. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.close(CancellationException("WebSocket session closed with code NORMAL."))
        setup.socket.event("message.delta", """{"text":"And you?"}""")
        setup.socket.event("message.complete", """{"text":"Fine. And you?","status":"complete"}""")

        setup.voice.chat.first { it.phase == VoicePhase.Listening && setup.spoken.isNotEmpty() }
        assertEquals(listOf("Fine. And you?"), setup.spoken)
        setup.voice.stopChat()
    }

    /** Ended before the turn starts, on a socket whose close throws: nothing escapes the cleanup. */
    @Test
    fun endingEarlyOnASocketThatThrowsWhenClosed() = runTest {
        val stream = Stream(closedByTheGateway = true)
        var opened = false
        val setup = chat(holdPrompts = true) { opened = true; stream }
        while (!opened) yield()

        setup.voice.stopChat()
        testScheduler.runCurrent()

        assertTrue(stream.closed)
        assertEquals(VoicePhase.Off, setup.voice.chat.value.phase)
    }

    /** A reply that was voiced to its end, on a socket that throws when it's closed afterwards, listens again. */
    @Test
    fun aVoicedReplyOnASocketTheGatewayClosedListensAgain() = runTest {
        val stream = Stream(closedByTheGateway = true)
        val setup = chat(recorder = OneRequest(requests = 2)) { stream }

        setup.socket.event("message.start")
        setup.socket.event("message.complete", """{"text":"Done.","status":"complete"}""")
        stream.appended.first { it == "Done." }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0)))
        stream.gateway.send(SpeechStreamEvent.End)

        setup.socket.submits.first { it.size == 2 }
        // Voiced as it streamed: not read again.
        assertTrue(setup.spoken.isEmpty())
        setup.voice.stopChat()
    }

    @Test
    fun aPlayerThatFailsKeepsNoAudioAndReadsTheFinishedReply() = runTest {
        val stream = Stream()
        val setup = chat(stream, Player(fails = true))

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was good. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0)))
        val pcm = setup.player.pcmChannel()
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(2, 0)))
        // Nobody would read it: the audio isn't piling up.
        while (!pcm.isClosedForReceive) yield()
        setup.socket.event("message.complete", """{"text":"It was good.","status":"complete"}""")

        setup.voice.chat.first { it.phase == VoicePhase.Listening && setup.spoken.isNotEmpty() }
        assertEquals(listOf("It was good."), setup.spoken)
        setup.voice.stopChat()
    }

    @Test
    fun whatWasHeldBackIsSentWhenTheReplyEnds() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"Try running `ls`"}""")
        stream.appended.first { it.isNotEmpty() }
        setup.socket.event("message.complete", """{"text":"Try running `ls`","status":"complete"}""")

        stream.appended.first { it == "Try running `ls`" }
        stream.gateway.send(SpeechStreamEvent.End)
        setup.voice.chat.first { it.phase == VoicePhase.Listening && stream.closed }
        assertTrue(stream.finished)
        setup.voice.stopChat()
    }

    /** Ended while the prompt is still going out: the speech socket opened beside it is closed, not left open. */
    @Test
    fun endingTheChatBeforeTheTurnStartsClosesTheSpeechSocket() = runTest {
        val stream = Stream()
        var opened = false
        val setup = chat(holdPrompts = true) { opened = true; stream }
        while (!opened) yield()

        setup.voice.stopChat()
        testScheduler.runCurrent()

        assertTrue(stream.closed)
    }

    /** An older gateway without the socket isn't asked again every turn. */
    @Test
    fun aGatewayWithoutTheSocketIsAskedOnce() = runTest {
        var opens = 0
        val setup = chat(recorder = OneRequest(requests = 2)) { opens++; null }

        setup.socket.event("message.start")
        setup.socket.event("message.complete", """{"text":"One.","status":"complete"}""")
        setup.socket.submits.first { it.size == 2 }
        setup.socket.event("message.start")
        setup.socket.event("message.complete", """{"text":"Two.","status":"complete"}""")

        setup.voice.chat.first { it.phase == VoicePhase.Listening && setup.spoken.size == 2 }
        assertEquals(listOf("One.", "Two."), setup.spoken)
        assertEquals(1, opens)
        setup.voice.stopChat()
    }

    @Test
    fun theReplySentIsWhatWasAddedOrWhereItWasRewritten() {
        assertEquals("world.", unsentSpeech("Hello ", "Hello world."))
        assertEquals("", unsentSpeech("Hello world.", "Hello world."))
        // Rewritten under it: from the word where they part, never half a word.
        assertEquals("car is red.", unsentSpeech("The cat", "The car is red."))
        assertEquals("", unsentSpeech("The cat is", "The car"))
    }

    @Test
    fun aReplyWithNoWordsDoesntShiftTheRest() {
        val withEmpty = listOf(
            ChatMessage.User("u", "Hi"),
            ChatMessage.Assistant("a1", ""),
            ChatMessage.Assistant("a2", "Hello."),
        )
        assertEquals("Hello.", replyText(withEmpty, 1))
        assertEquals(replyText(withEmpty, 1), replyText(withEmpty - withEmpty[1], 1))
    }

    private suspend fun Player.pcmChannel(): ReceiveChannel<ByteArray> {
        while (pcm == null) yield()
        return pcm!!
    }
}

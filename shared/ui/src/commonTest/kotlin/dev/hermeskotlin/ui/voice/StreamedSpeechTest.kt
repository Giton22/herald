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
import dev.hermeskotlin.core.voice.BargeIn
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
        /** Every method called, in order. */
        val calls = MutableStateFlow<List<String>>(emptyList())
        /** Prompts are taken but never answered, as on a gateway that's slow to. */
        var holdPrompts = false
        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            message["method"]?.jsonPrimitive?.contentOrNull?.let { method -> calls.update { it + method } }
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

    /**
     * Hears one request; then, over the reply, someone cuts in when the test says so ([cut]) and the recording
     * goes to the gateway, having [goneOn] talking once the voice held, or not. Listens for good after that.
     */
    private class CutsIn(val goneOn: Boolean = true) : VoiceRecorder {
        var recordings = 0
        val listening = MutableStateFlow(0)
        val cut = CompletableDeferred<Unit>()
        /** The cut was heard: by the listen it came in, or the next one when it came between two. */
        private var heardCut = false
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            if (recordings++ >= 1) awaitCancellation()
            onSpeech()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heardSpeech = true)
        }

        override fun finish() = Unit

        override suspend fun listenOver(
            bargeIn: BargeIn,
            playing: () -> Boolean,
            onTrip: () -> Unit,
            onLevel: (Float) -> Unit,
            onListening: () -> Unit,
        ): Recording? {
            listening.update { it + 1 }
            onListening()
            if (heardCut) awaitCancellation()
            cut.await()
            heardCut = true
            onTrip()
            return Recording(byteArrayOf(4, 5, 6), "audio/wav", heardSpeech = goneOn)
        }
    }

    /** [canHold]: the voice holds when someone seems to cut in; [holds] is each hold and letting go. */
    private class Player(val fails: Boolean = false, val canHold: Boolean = false) : SpeechPlayer {
        val holds = mutableListOf<Boolean>()
        override fun hold(on: Boolean): Boolean {
            holds += on
            return canHold
        }

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
        /** What each recording says, in turn; the last one again after that. */
        transcripts: List<String> = listOf("How was your day?"),
        /** The profile's config, as `GET /api/config` gives it. */
        config: String = """{"ok":true}""",
        streams: () -> SpeechStream?,
    ): Setup {
        val spoken = mutableListOf<String>()
        var transcribed = 0
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/transcribe" -> {
                        val transcript = transcripts[minOf(transcribed++, transcripts.lastIndex)]
                        respond("""{"ok":true,"transcript":"$transcript"}""", HttpStatusCode.OK, json)
                    }
                    "/api/config" -> respond(config, HttpStatusCode.OK, json)
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
    fun talkingOverTheVoiceStopsItAndSendsWhatWasSaid() = runTest {
        val recorder = CutsIn()
        val stream = Stream()
        val setup = chat(recorder = recorder, transcripts = listOf("How was your day?", "Wait, what about tomorrow?")) { stream }

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was a long day. First the meeting ran over. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking && it.bargeIn }
        recorder.listening.first { it == 1 }

        recorder.cut.complete(Unit)
        // The voice stops at once, and the agent with it: what it's writing answers what came before.
        setup.socket.calls.first { "session.interrupt" in it }
        assertTrue(stream.closed)
        setup.socket.event("message.complete", """{"text":"It was a long day. First the meeting ran over.","status":"interrupted"}""")

        // What was said over it goes as the next prompt, without listening first.
        setup.socket.submits.first { it.size == 2 }
        assertEquals("Wait, what about tomorrow?", setup.socket.submits.value[1]["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(1, recorder.recordings)
        // The finished reply isn't read out after all.
        assertTrue(setup.spoken.isEmpty())
        // And the microphone listens over the next reply too.
        recorder.listening.first { it >= 2 }
        setup.voice.stopChat()
    }

    @Test
    fun talkingWhileTheAgentWorksStopsTheTurn() = runTest {
        val recorder = CutsIn()
        val setup = chat(recorder = recorder, transcripts = listOf("How was your day?", "Never mind that, check the weather")) { null }

        setup.socket.event("message.start")
        setup.voice.chat.first { it.phase == VoicePhase.Thinking }
        // Listening again, over the agent at work, once the reply turned out not to be voiced as it's written.
        recorder.listening.first { it >= 1 }
        recorder.cut.complete(Unit)
        setup.socket.calls.first { "session.interrupt" in it }
        setup.socket.event("message.complete", """{"text":"","status":"interrupted"}""")

        setup.socket.submits.first { it.size == 2 }
        assertEquals("Never mind that, check the weather", setup.socket.submits.value[1]["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        // Stopped once, by the prompt itself: a second stop could land after it and stop the new turn.
        assertEquals(1, setup.socket.calls.value.count { it == "session.interrupt" })
        setup.voice.stopChat()
    }

    /**
     * Over a loudspeaker the phone can hear itself; that isn't anyone cutting in. A voice that can't hold is told
     * by its words: nothing is sent, the reply goes on, and the microphone listens over it again.
     */
    @Test
    fun itsOwnVoiceHeardBackIsNotSent() = runTest {
        val recorder = CutsIn()
        val stream = Stream()
        val setup = chat(recorder = recorder, transcripts = listOf("How was your day?", "first the meeting ran over")) { stream }

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was a long day. First the meeting ran over. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        recorder.listening.first { it == 1 }
        recorder.cut.complete(Unit)

        recorder.listening.first { it == 2 }
        assertEquals(VoicePhase.Speaking, setup.voice.chat.value.phase)
        assertFalse(stream.closed)
        assertEquals(1, setup.socket.submits.value.size)
        assertTrue(setup.socket.calls.value.none { "session.interrupt" in it })
        setup.voice.stopChat()
    }

    /** Over a loudspeaker, "stop" can come with some of the voice's own words before it: that still ends the chat. */
    @Test
    fun stopSaidAfterItsOwnWordsEndsTheChat() = runTest {
        val recorder = CutsIn()
        val stream = Stream()
        val setup = chat(recorder = recorder, transcripts = listOf("How was your day?", "first the meeting ran over. Stop!")) { stream }

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was a long day. First the meeting ran over. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        recorder.listening.first { it >= 1 }
        recorder.cut.complete(Unit)

        setup.voice.chat.first { it.phase == VoicePhase.Off }
        assertTrue(stream.closed)
        // The agent stops with the chat, and nothing is sent.
        setup.socket.calls.first { "session.interrupt" in it }
        assertEquals(1, setup.socket.submits.value.size)
    }

    /** Talk over it to cut in is offered only where the microphone listens over the voice, not wherever it's on. */
    @Test
    fun cuttingInIsntOfferedWhereTheMicrophoneDoesntListen() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was a long day. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        testScheduler.advanceUntilIdle()
        assertFalse(setup.voice.chat.value.bargeIn)
        setup.voice.stopChat()
    }

    /** A request that only ends in "stop" is a request, not the end of the chat. */
    @Test
    fun aCutInEndingInStopIsSent() = runTest {
        val recorder = CutsIn()
        val stream = Stream()
        val setup = chat(recorder = recorder, transcripts = listOf("How was your day?", "Take me to the bus stop")) { stream }

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was a long day. First the meeting ran over. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        recorder.listening.first { it >= 1 }
        recorder.cut.complete(Unit)
        setup.socket.calls.first { "session.interrupt" in it }
        setup.socket.event("message.complete", """{"text":"It was a long day.","status":"interrupted"}""")

        setup.socket.submits.first { it.size == 2 }
        assertEquals("Take me to the bus stop", setup.socket.submits.value[1]["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        setup.voice.stopChat()
    }

    /**
     * A speaker turned right up trips it with its own voice. The voice holds; nobody goes on talking, since its
     * echo stopped with it, so it goes on where it held, whatever the words: nothing sent, nothing stopped.
     */
    @Test
    fun aVoiceThatHeldGoesOnWhenNobodyWentOnTalking() = runTest {
        val recorder = CutsIn(goneOn = false)
        val stream = Stream()
        val player = Player(canHold = true)
        val setup = chat(player = player, recorder = recorder, transcripts = listOf("How was your day?", "a long day and")) { stream }

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was a long day. First the meeting ran over. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        recorder.listening.first { it == 1 }
        recorder.cut.complete(Unit)

        recorder.listening.first { it == 2 }
        assertEquals(listOf(true, false), player.holds)
        assertEquals(VoicePhase.Speaking, setup.voice.chat.value.phase)
        assertFalse(stream.closed)
        assertEquals(1, setup.socket.submits.value.size)
        assertTrue(setup.socket.calls.value.none { "session.interrupt" in it })
        setup.voice.stopChat()
    }

    /** A quick "stop" can be over before the voice held; it's still heard, and ends the chat. */
    @Test
    fun aQuickStopOverBeforeTheVoiceHeldStillEndsTheChat() = runTest {
        val recorder = CutsIn(goneOn = false)
        val stream = Stream()
        val player = Player(canHold = true)
        val setup = chat(player = player, recorder = recorder, transcripts = listOf("How was your day?", "stop")) { stream }

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was a long day. First the meeting ran over. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        recorder.listening.first { it == 1 }
        recorder.cut.complete(Unit)

        setup.voice.chat.first { it.phase == VoicePhase.Off }
        assertEquals(1, setup.socket.submits.value.size)
    }

    /** Someone who went on talking once the voice held is cutting in, even in words like the reply's own. */
    @Test
    fun someoneWhoWentOnTalkingOnceItHeldCutsIn() = runTest {
        val recorder = CutsIn(goneOn = true)
        val stream = Stream()
        val player = Player(canHold = true)
        val setup = chat(player = player, recorder = recorder, transcripts = listOf("How was your day?", "the meeting ran over again")) { stream }

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was a long day. First the meeting ran over. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        recorder.listening.first { it == 1 }
        recorder.cut.complete(Unit)

        setup.socket.calls.first { "session.interrupt" in it }
        assertTrue(stream.closed)
        setup.socket.event("message.complete", """{"text":"It was a long day.","status":"interrupted"}""")
        setup.socket.submits.first { it.size == 2 }
        assertEquals("the meeting ran over again", setup.socket.submits.value[1]["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf(true, false), player.holds)
        setup.voice.stopChat()
    }

    /**
     * Over the agent at work nothing plays, so there's no echo for a hold to stop: a word too quick to go on past the
     * hold ("no, wait") is still someone cutting in, told by what they said.
     */
    @Test
    fun aQuickWordOverTheAgentAtWorkIsSent() = runTest {
        val recorder = CutsIn(goneOn = false)
        val player = Player(canHold = true)
        val setup = chat(player = player, recorder = recorder, transcripts = listOf("How was your day?", "No, wait")) { null }

        setup.socket.event("message.start")
        setup.voice.chat.first { it.phase == VoicePhase.Thinking }
        recorder.listening.first { it >= 1 }
        recorder.cut.complete(Unit)
        setup.socket.calls.first { "session.interrupt" in it }
        setup.socket.event("message.complete", """{"text":"","status":"interrupted"}""")

        setup.socket.submits.first { it.size == 2 }
        assertEquals("No, wait", setup.socket.submits.value[1]["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf(true, false), player.holds)
        setup.voice.stopChat()
    }

    @Test
    fun withBargeInOffTheMicrophoneStaysClosedOverTheReply() = runTest {
        val recorder = CutsIn()
        val stream = Stream()
        val setup = chat(recorder = recorder, config = """{"voice":{"barge_in":false}}""") { stream }

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"All good."}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        assertFalse(setup.voice.chat.value.bargeIn)
        assertEquals(0, recorder.listening.value)
        assertEquals("Tap skip to talk again", hint(setup.voice.chat.value))
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

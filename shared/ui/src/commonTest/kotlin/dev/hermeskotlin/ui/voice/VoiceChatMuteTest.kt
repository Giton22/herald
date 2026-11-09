package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
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
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceChatMuteTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** The gateway's socket: creates the chat and takes prompts; nothing answers them. */
    private class Socket : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        val submits = MutableStateFlow<List<JsonObject>>(emptyList())
        override val incoming: Flow<String> = inbound.receiveAsFlow()
        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
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

        /** A request of the gateway's own, as a question Hermes stops on. */
        fun push(raw: String) {
            inbound.trySend(raw)
        }
    }

    /** Answers the first [answered] listens; every later one is a sentence still being spoken until it's cancelled. */
    private class MidSentence(val answered: Int = 1) : VoiceRecorder {
        var recordings = 0
        val started = MutableStateFlow(0)
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            recordings++
            started.value = recordings
            onSpeech()
            if (recordings > answered) {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
            }
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heardSpeech = true)
        }

        override fun finish() = Unit
    }

    private object Silent : SpeechPlayer {
        override suspend fun play(audio: SpokenAudio) = Unit
    }

    /**
     * A chained voice chat, started and its first spoken sentence sent. [answer]: the first turn is answered, so the
     * loop listens again. [transcribing] runs as each recording goes to be written down, by its number.
     */
    private suspend fun TestScope.chat(
        recorder: VoiceRecorder,
        answer: Boolean = true,
        transcribing: suspend (Int) -> Unit = {},
    ): Pair<Socket, VoiceController> {
        var transcribed = 0
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/transcribe" -> {
                        transcribing(++transcribed)
                        respond("""{"ok":true,"transcript":"Turn the lights down"}""", HttpStatusCode.OK, json)
                    }
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val socket = Socket()
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val voice = VoiceController(AudioApi(client), recorder, Silent, backgroundScope, backgroundScope, speechStreams = { _, _ -> null })
        voice.startChat(session, gateway, "rin")
        socket.submits.first { it.isNotEmpty() }
        if (answer) {
            // The first sentence gets an answer, so the loop listens again.
            socket.event("message.start")
            socket.event("message.delta", """{"text":"Dimming."}""")
            socket.event("message.complete")
        }
        return socket to voice
    }

    /** Muted while Hermes asks aloud: the microphone waits for unmute, rather than opening and closing on and on. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun aQuestionAskedWhileMutedWaitsForUnmute() = runTest {
        val recorder = MidSentence()
        val (socket, voice) = chat(recorder, answer = false)
        socket.event("message.start")
        voice.chat.first { it.phase == VoicePhase.Thinking }
        voice.toggleMute()
        socket.push("""{"jsonrpc":"2.0","id":"srq-3","method":"clarify","params":{"session_id":"rt9","questions":[{"qid":"q1","question":"Which room?","choices":["Kitchen","Hall"]}]}}""")

        voice.chat.first { it.phase == VoicePhase.Listening }
        advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(1, recorder.recordings, "the mic opened while muted")
        assertEquals(VoicePhase.Listening, voice.chat.value.phase)

        voice.toggleMute()
        recorder.started.first { it >= 2 }
        voice.stopChat()
    }

    /** Muted once the recording ended but before it's written down: it's dropped all the same. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun muteWhileItsWrittenDownDropsIt() = runTest {
        val recorder = MidSentence(answered = 2)
        val second = CompletableDeferred<Unit>()
        val transcribing = MutableStateFlow(0)
        val (socket, voice) = chat(recorder) { n ->
            transcribing.value = n
            if (n == 2) second.await()
        }
        transcribing.first { it == 2 }
        voice.chat.first { it.phase == VoicePhase.Transcribing }

        voice.toggleMute()
        second.complete(Unit)
        // Written down, then dropped: back to listening, the microphone shut.
        voice.chat.first { it.phase == VoicePhase.Listening }
        advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(1, socket.submits.value.size, "a sentence muted while it was written down was sent")
        assertEquals(2, recorder.recordings, "the mic opened again while muted")
        voice.stopChat()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun muteKillsTheRecordingInFlight() = runTest {
        val recorder = MidSentence()
        val (socket, voice) = chat(recorder)
        // The second sentence is being spoken: the mic is open and nothing has been heard yet.
        recorder.started.first { it >= 2 }

        voice.toggleMute()
        advanceTimeBy(100)
        testScheduler.runCurrent()

        // The half-spoken sentence dies with the recording: never transcribed, never sent.
        assertTrue(recorder.cancelled.isCompleted, "the recording in flight was not cancelled")
        assertEquals(1, socket.submits.value.size, "the abandoned sentence reached the gateway")

        // The mic stays shut while muted, then opens again on unmute.
        advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(2, recorder.recordings, "the mic opened again while muted")
        voice.toggleMute()
        recorder.started.first { it >= 3 }
        voice.stopChat()
    }
}

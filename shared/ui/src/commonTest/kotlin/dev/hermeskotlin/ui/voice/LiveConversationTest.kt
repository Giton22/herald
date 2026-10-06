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
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveConversationTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** The gateway's socket: creates the chat and takes prompts; the test plays the turn's events. */
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
                "prompt.submit" -> {
                    val queued = message["params"]?.jsonObject?.get("queued")?.jsonPrimitive?.contentOrNull == "true"
                    """{"status":"${if (queued) "queued" else "streaming"}"}""".also { submits.update { it + message } }
                }
                else -> "{}"
            }
            inbound.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) = Unit

        fun event(type: String, payload: String = "{}") {
            inbound.trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt9","payload":$payload}}""")
        }
    }

    private class FakeCall : LiveCall {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        override val events: ReceiveChannel<String> = incoming
        val sent = MutableStateFlow<List<JsonObject>>(emptyList())
        var answer: String? = null
        var closed = false

        override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
            this.answer = answer("v=0 offer\r\n")
        }

        override fun send(event: String): Boolean {
            sent.update { it + Json.parseToJsonElement(event).jsonObject }
            return true
        }

        override fun speaking() = false
        override fun micLevel() = 0f
        override fun setMuted(muted: Boolean) = Unit

        override fun close() {
            closed = true
            incoming.close()
        }

        fun receive(event: String) {
            incoming.trySend(event)
        }

        fun said(type: String) = sent.value.filter { it["type"]?.jsonPrimitive?.contentOrNull == type }.map { it["content"]!!.jsonPrimitive.content }
    }

    private object NoRecorder : VoiceRecorder {
        var recordings = 0
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            recordings++
            return Recording(ByteArray(0), "audio/wav", heardSpeech = false)
        }

        override fun finish() = Unit
    }

    private object Silent : SpeechPlayer {
        override suspend fun play(audio: SpokenAudio) = Unit
    }

    private fun http(mode: String, offers: MutableList<String>) = createHttpClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"$mode","available":true,"reason":null,"model":"gpt-live-1","voice":"marin"}""", HttpStatusCode.OK, json)
                "/api/audio/voice-live/session" -> {
                    offers += request.body.toByteArray().decodeToString()
                    respond("""{"ok":true,"session":{"id":"live-1"},"transport":{"type":"webrtc","sdp":"v=0 answer\r\n"}}""", HttpStatusCode.OK, json)
                }
                else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
            }
        },
        PersistentCookiesStorage(InMemoryKeyValueStore()),
    )

    @Test
    fun aRequestTheVoiceHandsOverIsAHermesTurnAndItsReplyGoesBackToTheVoice() = runTest {
        val offers = mutableListOf<String>()
        val client = http("gpt-live", offers)
        val socket = Socket()
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val call = FakeCall()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { call }

        voice.startChat(session, gateway, "work")
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        // The gateway traded the phone's offer for the vendor's answer, opening with the chat so far.
        assertEquals("v=0 answer\r\n", call.answer)
        assertTrue(offers.single().contains("v=0 offer\\r\\n"))

        call.receive("""{"type":"session.input_transcript.delta","delta":"What's the weather","start_ms":0,"end_ms":900}""")
        call.receive("""{"type":"session.input_transcript.delta","delta":" in Khobar?","start_ms":900,"end_ms":1500}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d1","type":"client","target":"backend"}}""")

        val submit = socket.submits.first { it.isNotEmpty() }.single()["params"]!!.jsonObject
        assertEquals("What's the weather in Khobar?", submit["text"]!!.jsonPrimitive.content)
        assertEquals("voice-live", submit["surface"]!!.jsonPrimitive.content)
        assertEquals("User: What's the weather in Khobar?", submit["voice_context"]!!.jsonPrimitive.content)
        voice.chat.first { it.phase == VoicePhase.Thinking }

        socket.event("message.start")
        socket.event("tool.start", """{"tool_id":"t1","name":"web_search","context":"weather Al Khobar"}""")
        socket.event("message.delta", """{"text":"It's **sunny** and 34 degrees. "}""")
        socket.event("message.delta", """{"text":"Light"}""")
        // A whole sentence goes to the voice while the reply is still streaming.
        call.sent.first { sent -> sent.any { it["type"]?.jsonPrimitive?.contentOrNull == "session.commentary.append" } }
        assertEquals(listOf("It's sunny and 34 degrees."), call.said("session.commentary.append"))
        assertEquals(listOf("Hermes is working: weather Al Khobar. Not done yet."), call.said("session.thinking.append"))
        assertEquals("weather Al Khobar", voice.chat.value.working)
        // The chip goes once the tool is done, while the reply still streams.
        socket.event("tool.complete", """{"tool_id":"t1"}""")
        voice.chat.first { it.working == null }

        socket.event("message.delta", """{"text":" wind"}""")
        socket.event("message.complete", """{"text":"It's **sunny** and 34 degrees. Light wind","status":"complete"}""")
        call.sent.first { sent -> sent.count { it["type"]?.jsonPrimitive?.contentOrNull == "session.commentary.append" } == 2 }
        assertEquals(listOf("It's sunny and 34 degrees.", "Light wind"), call.said("session.commentary.append"))
        assertTrue(call.sent.value.filter { it["type"]?.jsonPrimitive?.contentOrNull == "session.commentary.append" }.all { it["delegation_id"]!!.jsonPrimitive.content == "d1" })
        voice.chat.first { it.phase == VoicePhase.Listening }

        voice.stopChat()
        voice.chat.first { it.phase == VoicePhase.Off }
        assertTrue(call.closed)
    }

    private data class Queued(val socket: Socket, val session: ChatSession, val call: FakeCall, val voice: VoiceController) {
        fun toD2() = call.sent.value.filter { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d2" }
    }

    /** A call where request d2 is queued behind d1's turn, which ignored the stop. */
    private suspend fun TestScope.queuedBehindATurn(): Queued {
        val client = http("gpt-live", mutableListOf())
        val socket = Socket()
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val call = FakeCall()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { call }
        voice.startChat(session, gateway, null)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }

        call.receive("""{"type":"session.input_transcript.delta","delta":"Check the logs","start_ms":0,"end_ms":900}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d1"}}""")
        socket.submits.first { it.size == 1 }
        socket.event("message.start")
        socket.event("message.delta", """{"text":"Reading the old logs"}""")
        session.state.first { it.running }

        call.receive("""{"type":"session.input_transcript.delta","delta":"Actually check disk space","start_ms":2000,"end_ms":3000}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        socket.submits.first { it.size == 2 }
        session.state.first { state -> state.messages.any { it is ChatMessage.User && it.queued } }
        return Queued(socket, session, call, voice)
    }

    @Test
    fun aRequestQueuedBehindATurnThatWontStopIsAnsweredByItsOwnTurn() = runTest {
        val queued = queuedBehindATurn()
        val (socket, session, call, voice) = queued

        // The old turn ends; it isn't d2's answer, and d2 doesn't give up on it.
        socket.event("message.complete", """{"text":"Reading the old logs. Nothing new.","status":"complete"}""")
        session.state.first { !it.running }
        assertTrue(queued.toD2().isEmpty())

        socket.event("message.start")
        socket.event("message.complete", """{"text":"The disk is 40% full.","status":"complete"}""")
        call.sent.first { queued.toD2().isNotEmpty() }
        assertEquals(listOf("session.commentary.append"), queued.toD2().map { it["type"]!!.jsonPrimitive.content })
        assertEquals("The disk is 40% full.", queued.toD2().single()["content"]!!.jsonPrimitive.content)
        voice.stopChat()
    }

    @Test
    fun aQueuedRequestDroppedByAStopSaysSoInsteadOfFinished() = runTest {
        val queued = queuedBehindATurn()
        // Stop in the chat drops the queue, then the old turn ends.
        queued.session.interrupt()
        queued.socket.event("message.complete", """{"text":"Reading the old logs.","status":"interrupted"}""")
        queued.call.sent.first { queued.toD2().isNotEmpty() }
        assertEquals(listOf("That request was dropped before Hermes ran it."), queued.toD2().map { it["content"]!!.jsonPrimitive.content })
        queued.voice.stopChat()
    }

    @Test
    fun saying_stop_hangsUpWithoutAskingHermes() = runTest {
        val client = http("gpt-live", mutableListOf())
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        val call = FakeCall()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { call }

        voice.startChat(session, gateway, null)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Okay, stop.","start_ms":0,"end_ms":700}""")

        // Ended by the person: no error.
        assertEquals(VoiceChatState(), voice.chat.first { it.phase == VoicePhase.Off })
        assertTrue(call.closed)
        assertEquals("session.close", call.sent.value.last()["type"]!!.jsonPrimitive.content)
        assertTrue(session.state.value.messages.isEmpty())
    }

    @Test
    fun theVendorEndingTheCallSaysWhy() = runTest {
        val client = http("gpt-live", mutableListOf())
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        val call = FakeCall()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { call }

        voice.startChat(session, gateway, null)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        call.receive("""{"type":"session.closed","reason":"idle_timeout","usage":{"seconds":61}}""")

        assertEquals("The live call ended: idle_timeout", voice.chat.first { it.phase == VoicePhase.Off }.error)
    }

    @Test
    fun aRefusedCallSaysWhatTheVendorSaid() = runTest {
        // The gateway's 502 when OpenAI turns the session down, as a phone saw it.
        val body = """{"detail":"GPT-Live session creation failed (429): {\n    \"error\": {\n        \"message\": \"You have no credits remaining.\",\n        \"code\": \"credit_balance_exhausted\"\n    }\n}"}"""
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"gpt-live","available":true}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/session" -> respond(body, HttpStatusCode.BadGateway, json)
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        val call = FakeCall()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { call }

        voice.startChat(session, gateway, null)

        assertEquals("GPT-Live couldn't start: You have no credits remaining.", voice.chat.first { it.phase == VoicePhase.Off && it.error != null }.error)
        assertTrue(call.closed)
    }

    @Test
    fun aProfileOnTheChainedModeKeepsDesktopsVoiceChat() = runTest {
        val client = http("chained", mutableListOf())
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        var calls = 0
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { calls++; FakeCall() }
        NoRecorder.recordings = 0

        voice.startChat(session, gateway, null)
        voice.chat.first { it.phase == VoicePhase.Listening && !it.live }
        voice.stopChat()

        assertEquals(0, calls)
    }

    private class Keeper(private val grants: Boolean) : VoiceKeepAlive {
        var holding = false
        var onEnd: (() -> Unit)? = null
        var onLost: (() -> Unit)? = null
        override fun hold(onEnd: () -> Unit, onLost: () -> Unit): Boolean {
            holding = grants
            this.onEnd = onEnd
            this.onLost = onLost
            return grants
        }

        override fun release() {
            holding = false
        }
    }

    @Test
    fun aHeldCallGoesOnOutOfSightAndItsNotificationEndsIt() = runTest {
        val client = http("gpt-live", mutableListOf())
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        val call = FakeCall()
        val keeper = Keeper(grants = true)
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope, keeper) { call }

        voice.startChat(session, gateway, null)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        assertTrue(keeper.holding)
        voice.onBackground()
        assertTrue(voice.chat.value.live && !call.closed)

        keeper.onEnd!!()
        assertEquals(VoiceChatState(), voice.chat.first { it.phase == VoicePhase.Off })
        assertTrue(call.closed)
        assertFalse(keeper.holding)
    }

    @Test
    fun endingWhileTheModeIsStillBeingCheckedClosesThePanel() = runTest {
        val statusAsked = CompletableDeferred<Unit>()
        val client = createHttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/api/audio/voice-live/status") {
                    statusAsked.complete(Unit)
                    awaitCancellation()
                }
                respond("""{"ok":true}""", HttpStatusCode.OK, json)
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        val keeper = Keeper(grants = true)
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope, keeper) { FakeCall() }

        voice.startChat(session, gateway, null)
        statusAsked.await()
        voice.stopChat()
        assertEquals(VoiceChatState(), voice.chat.first { it.phase == VoicePhase.Off })
        assertFalse(keeper.holding)
    }

    @Test
    fun aHoldRefusedAfterTheAppWentAwayEndsTheCall() = runTest {
        val client = http("gpt-live", mutableListOf())
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        val call = FakeCall()
        val keeper = Keeper(grants = true)
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope, keeper) { call }

        voice.startChat(session, gateway, null)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        voice.onBackground()
        assertTrue(voice.chat.value.live)
        // Android refused the service only once it started.
        keeper.onLost!!()
        voice.chat.first { it.phase == VoicePhase.Off }
        assertTrue(call.closed)
    }

    @Test
    fun aCallThatCouldntBeHeldEndsWhenTheAppGoesAway() = runTest {
        val client = http("gpt-live", mutableListOf())
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        val call = FakeCall()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope, Keeper(grants = false)) { call }

        voice.startChat(session, gateway, null)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        voice.onBackground()
        voice.chat.first { it.phase == VoicePhase.Off }
        assertTrue(call.closed)
    }
}

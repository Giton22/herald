package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.InputAnswers
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.phone.CallReason
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.SubscriptionLiveProtocol
import dev.hermeskotlin.core.voice.DeviceLock
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import io.ktor.client.engine.mock.MockEngineConfig

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
        var resume = """{"session_id":"rt9","running":true}"""
        var resumeGate: CompletableDeferred<Unit>? = null
        val resumes = MutableStateFlow<List<JsonObject>>(emptyList())
        val submits = MutableStateFlow<List<JsonObject>>(emptyList())
        override val incoming: Flow<String> = inbound.receiveAsFlow()

        /** What the phone answered the gateway's own requests (an approval, a question) with. */
        val answers = MutableStateFlow<List<JsonObject>>(emptyList())

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["method"] == null && message["result"] != null) return answers.update { it + message }
            val id = message["id"] ?: return
            val result = when (message["method"]?.jsonPrimitive?.contentOrNull) {
                "session.create" -> """{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}"""
                "session.resume" -> {
                    resumes.update { it + message }
                    resumeGate?.await()
                    resume
                }
                "prompt.submit" -> {
                    val queued = message["params"]?.jsonObject?.get("queued")?.jsonPrimitive?.contentOrNull == "true"
                    """{"status":"${if (queued) "queued" else "streaming"}"}""".also { submits.update { it + message } }
                }
                else -> "{}"
            }
            inbound.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override fun sendGuarded(text: String, canSend: () -> Boolean): Boolean {
            if (!canSend()) return false
            answers.update { it + Json.parseToJsonElement(text).jsonObject }
            return true
        }

        override suspend fun close(code: Short, reason: String) = Unit

        fun push(raw: String) {
            inbound.trySend(raw)
        }

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

        /** When the voice says what it's handed, as the real one does; null for one whose transcript has no times. */
        var spokenAt: Long? = 1_000

        /** What the voice says when it's handed a question; null: as a real voice asks it. */
        var asks: String? = null

        override fun send(event: String): Boolean {
            val sent = Json.parseToJsonElement(event).jsonObject
            this.sent.update { it + sent }
            // The voice asks what Hermes asks: its words come back as the call's transcript.
            val content = (sent["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (content.startsWith("Hermes needs an answer")) {
                val at = spokenAt?.let { ""","start_ms":$it,"end_ms":${it + 900}""" }.orEmpty()
                val asked = asks ?: if ("approval" in content) "Should I go ahead, yes or no?" else "Which one would you like?"
                receive("""{"type":"session.output_transcript.delta","delta":"$asked"$at}""")
            }
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

    private fun http(mode: String, offers: MutableList<String>, testDispatcher: CoroutineDispatcher? = null) = createHttpClient(
        MockEngine(MockEngineConfig().apply {
            testDispatcher?.let { dispatcher = it }
            addHandler { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"$mode","available":true,"reason":null,"model":"gpt-live-1","voice":"marin"}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/session" -> {
                        offers += request.body.toByteArray().decodeToString()
                        respond("""{"ok":true,"session":{"id":"live-1"},"transport":{"type":"webrtc","sdp":"v=0 answer\r\n"}}""", HttpStatusCode.OK, json)
                    }
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            }
        }),
        PersistentCookiesStorage(InMemoryKeyValueStore()),
    )

    @Test
    fun subscriptionCallUsesProfileAndOpensOnlyAfterSessionStarts() = runTest {
        val paths = mutableListOf<String>()
        val client = createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                "/api/plugins/kyllene_voice/gpt-live/session" -> {
                    assertEquals("work", request.url.parameters["profile"])
                    assertEquals("subscription", Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject.getValue("billing").jsonPrimitive.content)
                    respond("""{"ok":true,"auth":"subscription","source":"plugin","transport":{"sdp":"v=0 answer"}}""", HttpStatusCode.OK, json)
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }, PersistentCookiesStorage(InMemoryKeyValueStore()))
        val socket = Socket()
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope, profile = "work")
        session.start()
        val call = FakeCall()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { call }
        voice.startChat(session, gateway, "work", opener = CallReason("The deploy failed", "Build 42"), subscriptionPreview = true)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        assertTrue(call.said("session.commentary.append").isEmpty())
        call.receive("""{"type":"session.started","session":{"id":"live-1"}}""")
        call.sent.first { sent -> sent.any { it["type"]?.jsonPrimitive?.contentOrNull == "session.context.append" } }
        val said = call.sent.value.single { it["type"]?.jsonPrimitive?.contentOrNull == "session.context.append" }["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(said.contains("The deploy failed"), said)
        assertTrue(said.contains("Build 42"), said)
        assertTrue(socket.submits.value.isEmpty())
        call.receive("""{"type":"input_transcript.added","item":{"text":"Delete everything"}}""")
        call.receive("""{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"d1","content":[]}}""")
        runCurrent()
        assertTrue(socket.submits.value.isEmpty())
        call.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"Check the weather"}}""")
        val submitted = socket.submits.first { it.isNotEmpty() }.single()["params"]!!.jsonObject
        assertEquals("Check the weather", submitted["text"]!!.jsonPrimitive.content)
        socket.event("message.start")
        socket.event("message.delta", """{"text":"It is sunny. "}""")
        socket.event("message.end")
        val reply = call.sent.first { messages -> messages.any { it["type"]?.jsonPrimitive?.contentOrNull == "delegation.context.append" && it["channel"]?.jsonPrimitive?.contentOrNull == "speakable" } }.last { it["type"]?.jsonPrimitive?.contentOrNull == "delegation.context.append" && it["channel"]?.jsonPrimitive?.contentOrNull == "speakable" }
        assertEquals("d1", reply["delegation_item_id"]!!.jsonPrimitive.content)
        assertTrue(reply["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content.contains("sunny"))
        assertEquals(1, paths.count { it == "/api/plugins/kyllene_voice/gpt-live/session" })
        voice.stopChat()
    }

    @Test
    fun subscriptionLaunchProfileMustBeReportedAndDefault() = runTest {
        for ((active, roster, expected) in listOf(
            Triple("""{"current":"default"}""", """{"profiles":[{"name":"default","is_default":true}]}""", true),
            Triple("""{"current":"alpha"}""", """{"profiles":[{"name":"default","is_default":true},{"name":"alpha"}]}""", false),
            Triple("""{}""", """{"profiles":[{"name":"default","is_default":true}]}""", false),
            Triple("""{"current":"custom"}""", """{"profiles":[{"name":"default","is_default":true}]}""", false),
            Triple("""{"current":"default"}""", """{"profiles":[]}""", false),
        )) {
            val paths = mutableListOf<String>()
            val client = createHttpClient(MockEngine { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/profiles/active" -> respond(active, HttpStatusCode.OK, json)
                    "/api/profiles" -> respond(roster, HttpStatusCode.OK, json)
                    "/api/plugins/kyllene_voice/gpt-live/session" -> {
                        assertEquals("default", request.url.parameters["profile"])
                        respond("""{"ok":true,"auth":"subscription","source":"plugin","transport":{"sdp":"v=0 answer"}}""", HttpStatusCode.OK, json)
                    }
                    else -> error("Unexpected request: ${request.url}")
                }
            }, PersistentCookiesStorage(InMemoryKeyValueStore()))
            val socket = Socket()
            val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
            connection.start(gateway)
            connection.state.first { it is ConnectionState.Connected }
            val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
            session.start()
            var created = 0
            val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope, profiles = ProfilesApi(client)) { created++; FakeCall() }
            voice.startChat(session, gateway, null, subscriptionPreview = true)
            if (expected) voice.chat.first { it.live && it.phase == VoicePhase.Listening }
            else voice.chat.first { it.phase == VoicePhase.Off && it.error != null }
            assertEquals(if (expected) 1 else 0, created, active)
            assertEquals(if (expected) 1 else 0, paths.count { it == "/api/plugins/kyllene_voice/gpt-live/session" }, active)
            assertTrue(paths.none { it.contains("/api/audio/") })
            voice.stopChat()
        }
    }

    @Test
    fun subscriptionHistoryFailureNeverCreatesCallOrFallsBack() = runTest {
        val paths = mutableListOf<String>()
        val client = createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when {
                request.url.encodedPath == "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                request.url.encodedPath.endsWith("/messages") -> respond("offline", HttpStatusCode.ServiceUnavailable)
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        }, PersistentCookiesStorage(InMemoryKeyValueStore()))
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, "stored-1", null, connection, SessionsApi(client), backgroundScope, profile = "work")
        session.start()
        var created = 0
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { created++; FakeCall() }
        voice.startChat(session, gateway, "work", subscriptionPreview = true)
        val state = voice.chat.first { it.phase == VoicePhase.Off && it.error != null }
        assertTrue(state.error!!.contains("could not read the chat history"))
        assertEquals(0, created)
        assertTrue(paths.none { it.contains("kyllene") || it.contains("/api/audio/") })
    }

    @Test
    fun subscriptionFailureAndProfileMismatchNeverFallBack() = runTest {
        val paths = mutableListOf<String>()
        val client = createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                "/api/plugins/kyllene_voice/gpt-live/session" -> respond("""{"ok":true,"auth":"api","source":"plugin","transport":{"sdp":"v=0 answer"}}""", HttpStatusCode.OK, json)
                else -> error("Unexpected fallback: ${request.url}")
            }
        }, PersistentCookiesStorage(InMemoryKeyValueStore()))
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope, profile = "work")
        session.start()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { FakeCall() }
        voice.startChat(session, gateway, "other", subscriptionPreview = true)
        voice.chat.first { it.phase == VoicePhase.Off && it.error != null }
        assertTrue(paths.none { it.contains("audio") || it.contains("kyllene") })
        voice.startChat(session, GatewayUrl.parse("https://other.example"), "work", subscriptionPreview = true)
        voice.chat.first { it.phase == VoicePhase.Off && it.error != null }
        assertTrue(paths.none { it.contains("audio") || it.contains("kyllene") })
        voice.startChat(session, gateway, "work", subscriptionPreview = true)
        voice.chat.first { it.phase == VoicePhase.Off && it.error != null }
        assertEquals(1, paths.count { it == "/api/plugins/kyllene_voice/gpt-live/session" })
        assertTrue(paths.none { it.contains("/api/audio/") })
    }


    @Test
    fun aCallTheAgentPlacedOpensWithWhyOnceTheVoiceIsUp() = runTest {
        val client = http("gpt-live", mutableListOf())
        val socket = Socket()
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val call = FakeCall()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { call }

        voice.startChat(session, gateway, "work", opener = CallReason("The deploy failed", "Build 42"))
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        // Nothing is said before the voice is up: it would drop it.
        assertTrue(call.said("session.commentary.append").isEmpty())
        call.receive("""{"type":"session.started","session":{"id":"live-1"}}""")
        call.sent.first { sent -> sent.any { it["type"]?.jsonPrimitive?.contentOrNull == "session.commentary.append" } }
        val said = call.said("session.commentary.append").single()
        assertTrue(said.contains("tell them why, briefly: The deploy failed"), said)
        assertTrue(said.contains("Build 42"), said)
        // Said by the voice, not a request: no Hermes turn starts.
        assertTrue(socket.submits.value.isEmpty())
        voice.stopChat()
    }

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
        call.receive("""{"type":"session.input_transcript.delta","delta":" in Chicago?","start_ms":900,"end_ms":1500}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d1","type":"client","target":"backend"}}""")

        val submit = socket.submits.first { it.isNotEmpty() }.single()["params"]!!.jsonObject
        assertEquals("What's the weather in Chicago?", submit["text"]!!.jsonPrimitive.content)
        assertEquals("voice-live", submit["surface"]!!.jsonPrimitive.content)
        assertEquals("User: What's the weather in Chicago?", submit["voice_context"]!!.jsonPrimitive.content)
        voice.chat.first { it.phase == VoicePhase.Thinking }

        socket.event("message.start")
        socket.event("tool.start", """{"tool_id":"t1","name":"web_search","context":"weather Chicago"}""")
        socket.event("message.delta", """{"text":"It's **sunny** and 34 degrees. "}""")
        socket.event("message.delta", """{"text":"Light"}""")
        // A whole sentence goes to the voice while the reply is still streaming.
        call.sent.first { sent -> sent.any { it["type"]?.jsonPrimitive?.contentOrNull == "session.commentary.append" } }
        assertEquals(listOf("It's sunny and 34 degrees."), call.said("session.commentary.append"))
        assertEquals(listOf("Hermes is working: weather Chicago. Not done yet."), call.said("session.thinking.append"))
        assertEquals("weather Chicago", voice.chat.value.working)
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun shortcutRingbackWaitsThreeSecondsBetweenClipsAndStopsWhenLiveAnswers() = runTest {
        val player = RingbackPlayer()
        val (voice, call) = ringingCall(player)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        runCurrent()
        assertTrue(player.playing)
        advanceTimeBy(750); runCurrent()
        assertFalse(player.playing)
        advanceTimeBy(2_999); runCurrent()
        assertEquals(1, player.clips)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, player.clips)
        call.receive("""{"type":"session.started"}""")
        runCurrent()
        assertFalse(player.playing)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, player.clips)
        assertTrue(voice.chat.value.live)
        voice.stopChat()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun ringbackStopsOnEarlySpeechHangupAndConnectionFailureButManualCallsStayQuiet() = runTest {
        for (ending in listOf("speech", "hangup", "failure", "manual", "playback", "fallback", "error")) {
            val player = RingbackPlayer(fails = ending == "playback")
            val (voice, call) = ringingCall(player, enabled = ending != "manual", mode = if (ending == "fallback") "chained" else "gpt-live")
            voice.chat.first { it.phase == VoicePhase.Listening }
            runCurrent()
            when (ending) {
                "speech" -> call.receive("""{"type":"session.output_transcript.delta","delta":"Hello"}""")
                "hangup" -> voice.stopChat()
                "error" -> call.receive("""{"type":"error","error":{"message":"Provider unavailable"}}""")
                "failure" -> call.receive("""{"type":"session.closed","reason":"failed"}""")
            }
            runCurrent()
            if (ending != "playback") assertFalse(player.playing, ending)
            val count = player.clips
            advanceTimeBy(4_000); runCurrent()
            assertEquals(count, player.clips, ending)
            if (ending == "manual") assertEquals(0, count)
            if (ending == "playback") assertTrue(voice.chat.value.live)
            voice.stopChat(); runCurrent()
        }
    }

    private class RingbackPlayer(val fails: Boolean = false) : SpeechPlayer {
        var clips = 0
        var playing = false
        override suspend fun play(audio: SpokenAudio) = Unit
        override suspend fun playRingback() {
            clips++
            if (fails) error("Speaker unavailable")
            playing = true
            try { delay(750) } finally { playing = false }
        }
    }

    private fun TestScope.ringingCall(player: SpeechPlayer, enabled: Boolean = true, mode: String = "gpt-live"): Pair<VoiceController, FakeCall> {
        val client = http(mode, mutableListOf(), StandardTestDispatcher(testScheduler))
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        val call = FakeCall()
        val recorder = object : VoiceRecorder {
            override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording = awaitCancellation()
            override fun finish() = Unit
        }
        val voice = VoiceController(AudioApi(client), recorder, player, backgroundScope, backgroundScope) { call }
        voice.startChat(session, gateway, null, ringback = enabled)
        return voice to call
    }

    /** A live call whose request d1 started a turn that is now running. */
    private suspend fun TestScope.callWithATurn(lock: DeviceLock = DeviceLock.None): Queued {
        val client = http("gpt-live", mutableListOf())
        val socket = Socket()
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val call = FakeCall()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope, deviceLock = lock) { call }
        voice.startChat(session, gateway, null)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        call.receive("""{"type":"session.started","session":{"id":"live-1"}}""")
        call.receive("""{"type":"session.input_transcript.delta","delta":"Clean the build","start_ms":0,"end_ms":900}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d1"}}""")
        socket.submits.first { it.size == 1 }
        socket.event("message.start")
        session.state.first { it.running }
        return Queued(socket, session, call, voice)
    }

    private data class SubscriptionRequestCall(val socket: Socket, val session: ChatSession, val call: FakeCall, val state: MutableStateFlow<VoiceChatState>)

    private suspend fun TestScope.subscriptionRequestCall(lock: DeviceLock = DeviceLock.None, method: String = "approval", customParams: String? = null): SubscriptionRequestCall {
        val params = customParams ?: if (method == "approval") """"command":"example","choices":["once","always","deny"]""" else """"question":"Color?","choices":["Red","Blue"]"""
        val client = http("gpt-live", mutableListOf())
        val socket = Socket().apply { resume = """{"session_id":"rt9","running":true,"open_requests":[{"id":"bound","method":"$method","params":{"session_id":"rt9",$params}}]}""" }
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, "stored-9", null, connection, SessionsApi(client), backgroundScope, profile = "work")
        session.start()
        val request = session.state.first { it.inputRequests.isNotEmpty() }.inputRequests.single()
        val origin = dev.hermeskotlin.core.wear.CallOrigin(gateway, "work", "rt9", "bound", method)
        val call = FakeCall()
        val state = MutableStateFlow(VoiceChatState())
        backgroundScope.launch { LiveConversation(call, session, state, lock = lock, subscriptionProtocol = SubscriptionLiveProtocol(), incoming = IncomingVoiceRequest(origin, "stored-9", request)).run {} }
        call.receive("""{"type":"session.started"}""")
        runCurrent()
        return SubscriptionRequestCall(socket, session, call, state)
    }

    private fun FakeCall.subscriptionAsked(method: String = "approval") {
        val question = if (method == "approval") "Should I run example, yes or no?" else "Color?"
        receive("""{"type":"output_transcript.added","item":{"text":"$question"}}""")
    }

    private fun FakeCall.subscriptionFinal(text: String) {
        receive("""{"type":"turn.done","turn":{"role":"user","transcript":"$text"}}""")
    }

    @Test
    fun subscriptionUnrelatedApprovalQuestionCannotAuthorize() = runTest {
        val (socket, _, call) = subscriptionRequestCall()
        call.receive("""{"type":"output_transcript.added","item":{"text":"Should I go ahead, yes or no?"}}""")
        call.subscriptionFinal("yes")
        advanceTimeBy(10_000); runCurrent()
        assertTrue(socket.answers.value.isEmpty())
        call.close()
    }

    @Test
    fun subscriptionLongRequestsPresentTheWholeIdentityBeforeTakingAnAnswer() = runTest {
        for (method in listOf("approval", "clarify")) {
            val identity = if (method == "approval") "Review ${"details ".repeat(30)}then delete all backups"
                else "Which ${"details ".repeat(30)}should be kept?"
            val field = if (method == "approval") "description" else "question"
            val params = "\"$field\":${JsonPrimitive(identity)},\"command\":\"example\",\"choices\":[${if (method == "approval") "\"once\",\"deny\"" else "\"Red\",\"Blue\""}]"
            val (socket, _, call) = subscriptionRequestCall(method = method, customParams = params)
            val presented = call.sent.value.filter { it["type"]?.jsonPrimitive?.contentOrNull == "session.context.append" }
                .joinToString("") { it.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content }
            assertTrue(identity in presented, presented)
            // A partial description/question must never pass the existing presentation gate.
            val partial = identity.take(100) + "? yes or no?"
            call.receive("""{"type":"output_transcript.added","item":{"text":${JsonPrimitive(partial)}}}""")
            call.subscriptionFinal(if (method == "approval") "yes" else "Blue")
            advanceTimeBy(10_000); runCurrent()
            assertTrue(socket.answers.value.isEmpty(), method)
            call.close()
            val (freshSocket, _, freshCall) = subscriptionRequestCall(method = method, customParams = params)
            freshCall.receive("""{"type":"output_transcript.added","item":{"text":${JsonPrimitive(presented)}}}""")
            freshCall.subscriptionFinal(if (method == "approval") "yes" else "Blue")
            advanceTimeBy(10_000); runCurrent()
            assertEquals(1, freshSocket.answers.value.size, method)
            freshCall.close()
        }
    }

    @Test
    fun subscriptionDuplicateFinalCannotBecomePrompt() = runTest {
        val (socket, _, call) = subscriptionRequestCall()
        call.subscriptionAsked()
        call.subscriptionFinal("yes")
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, socket.answers.value.size)
        call.subscriptionFinal("yes")
        call.receive("""{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"duplicate","content":[]}}""")
        advanceTimeBy(10_000); runCurrent()
        assertTrue(socket.submits.value.isEmpty())
        call.subscriptionFinal("Check disk space")
        call.receive("""{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"next","content":[]}}""")
        advanceTimeBy(10_000); runCurrent()
        assertEquals("Check disk space", socket.submits.value.single().getValue("params").jsonObject.getValue("text").jsonPrimitive.content)
        call.close()
    }

    @Test
    fun subscriptionCorrectedFinalReplacesPartialCaption() = runTest {
        val (_, _, call, state) = subscriptionRequestCall()
        call.subscriptionAsked()
        call.receive("""{"type":"input_transcript.added","item":{"text":"yes"}}""")
        call.subscriptionFinal("no")
        runCurrent()
        assertEquals("no", state.value.caption)
    }

    @Test
    fun subscriptionLateDelegationDoesNotSubmitAnswerOrContaminateNextPrompt() = runTest {
        for (method in listOf("approval", "clarify")) {
            val (socket, _, call) = subscriptionRequestCall(method = method)
            call.subscriptionAsked(method)
            call.subscriptionFinal(if (method == "approval") "yes" else "Blue")
            advanceTimeBy(10_000); runCurrent()
            assertEquals(1, socket.answers.value.size)
            call.receive("""{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"late","content":[]}}""")
            advanceTimeBy(10_000); runCurrent()
            assertTrue(socket.submits.value.isEmpty(), "late $method answer was submitted as a prompt")
            call.subscriptionFinal("Check disk space")
            call.receive("""{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"next","content":[]}}""")
            advanceTimeBy(10_000); runCurrent()
            val submitted = socket.submits.value.single().getValue("params").jsonObject
            assertEquals("Check disk space", submitted.getValue("text").jsonPrimitive.content)
            val context = submitted.getValue("voice_context").jsonPrimitive.content
            assertFalse("User: ${if (method == "approval") "yes" else "Blue"}" in context, context)
            call.close()
        }
    }

    @Test
    fun subscriptionUnclearAnswerCanBeRetriedHandsFree() = runTest {
        for (method in listOf("approval", "clarify")) {
            val (socket, _, call) = subscriptionRequestCall(method = method)
            call.subscriptionAsked(method)
            call.subscriptionFinal(if (method == "approval") "maybe" else "I don't know")
            advanceTimeBy(10_000); runCurrent()
            assertTrue(socket.answers.value.isEmpty())
            assertTrue(socket.submits.value.isEmpty())
            call.subscriptionAsked(method)
            call.subscriptionFinal(if (method == "approval") "no" else "Blue")
            advanceTimeBy(10_000); runCurrent()
            assertEquals(if (method == "approval") "deny" else "Blue", socket.answers.value.single().getValue("result").jsonObject.getValue(if (method == "approval") "choice" else "answer").jsonPrimitive.content)
            assertTrue(socket.submits.value.isEmpty())
            call.close()
        }
    }

    @Test
    fun subscriptionHandsFreeAnswersGenuineRequestsOnce() = runTest {
        for ((method, spoken, expected) in listOf(
            Triple("approval", "yes", "once"), Triple("approval", "always", "always"),
            Triple("approval", "no", "deny"), Triple("clarify", "Blue", "Blue"),
        )) {
            val (socket, session, call) = subscriptionRequestCall(method = method)
            call.subscriptionAsked(method)
            call.subscriptionFinal(spoken)
            advanceTimeBy(10_000); runCurrent()
            val answer = socket.answers.value.single()
            assertEquals("bound", answer.getValue("id").jsonPrimitive.content)
            assertEquals(expected, answer.getValue("result").jsonObject.getValue(if (method == "approval") "choice" else "answer").jsonPrimitive.content)
            assertTrue(session.state.value.inputRequests.isEmpty())
            call.subscriptionFinal(spoken)
            advanceTimeBy(10_000); runCurrent()
            assertEquals(1, socket.answers.value.size)
            assertTrue(socket.submits.value.isEmpty())
            call.close()
        }
    }

    @Test
    fun subscriptionRequiresPresentedQuestionAndAuthoritativeAnswer() = runTest {
        for (method in listOf("approval", "clarify")) {
            val (socket, _, call) = subscriptionRequestCall(method = method)
            call.subscriptionFinal(if (method == "approval") "yes" else "Blue")
            advanceTimeBy(10_000); runCurrent()
            assertTrue(socket.answers.value.isEmpty(), "unpresented $method")
            call.subscriptionAsked(method)
            call.receive("""{"type":"input_transcript.added","item":{"text":"${if (method == "approval") "yes" else "Blue"}"}}""")
            advanceTimeBy(10_000); runCurrent()
            assertTrue(socket.answers.value.isEmpty(), "partial $method")
            call.subscriptionFinal(if (method == "approval") "maybe" else "I don't know")
            advanceTimeBy(10_000); runCurrent()
            assertTrue(socket.answers.value.isEmpty(), "ambiguous $method")
            assertTrue(socket.submits.value.isEmpty())
            call.close()
        }
    }

    @Test
    fun subscriptionApprovalHonorsLockBeforeAndDuringVerification() = runTest {
        var locked = true
        val (socket, session, call) = subscriptionRequestCall(DeviceLock { locked })
        call.subscriptionAsked()
        call.subscriptionFinal("yes")
        advanceTimeBy(10_000); runCurrent()
        assertTrue(socket.answers.value.isEmpty())
        locked = false
        advanceTimeBy(2_000); runCurrent()
        call.subscriptionAsked()
        runCurrent()
        socket.resumeGate = CompletableDeferred()
        call.subscriptionFinal("yes")
        socket.resumes.first { it.size >= 2 }
        locked = true
        socket.resumeGate!!.complete(Unit)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(socket.answers.value.isEmpty())
        assertEquals("bound", session.state.value.inputRequests.single().id)
        assertTrue(socket.submits.value.isEmpty())
    }

    @Test
    fun subscriptionNeverAnswersCanceledReplacedOrDifferentRequest() = runTest {
        for (scenario in listOf("canceled", "replaced", "different")) {
            val (socket, session, call) = subscriptionRequestCall()
            call.subscriptionAsked()
            runCurrent()
            socket.resumeGate = CompletableDeferred()
            call.subscriptionFinal("yes")
            socket.resumes.first { it.size >= 2 }
            when (scenario) {
                "canceled" -> {
                    socket.event("request.cancel", """{"id":"bound"}""")
                    session.state.first { it.inputRequests.isEmpty() }
                    socket.resume = """{"session_id":"rt9","running":true,"open_requests":[]}"""
                }
                "replaced" -> socket.resume = """{"session_id":"rt9","running":true,"open_requests":[{"id":"bound","method":"approval","params":{"session_id":"rt9","command":"different","choices":["once","always","deny"]}}]}"""
                else -> socket.resume = """{"session_id":"rt9","running":true,"open_requests":[{"id":"other","method":"approval","params":{"session_id":"rt9","command":"example","choices":["once","always","deny"]}}]}"""
            }
            socket.resumeGate!!.complete(Unit)
            advanceTimeBy(10_000); runCurrent()
            assertTrue(socket.answers.value.isEmpty(), scenario)
            assertTrue(socket.submits.value.isEmpty(), scenario)
            call.close()
        }
    }

    @Test
    fun subscriptionUsesFinalCorrectionForGenuineQuestion() = runTest {
        for (method in listOf("approval", "clarify")) {
            val client = http("gpt-live", mutableListOf())
            val params = if (method == "approval") """"command":"example","choices":["once","deny"]""" else """"question":"Color?","choices":["Red","Blue"]"""
            val socket = Socket().apply { resume = """{"session_id":"rt9","running":true,"open_requests":[{"id":"bound","method":"$method","params":{"session_id":"rt9",$params}}]}""" }
            val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
            connection.start(gateway)
            connection.state.first { it is ConnectionState.Connected }
            val session = ChatSession(gateway, "stored-9", null, connection, SessionsApi(client), backgroundScope, profile = "work")
            session.start()
            val request = session.state.first { it.inputRequests.isNotEmpty() }.inputRequests.single()
            val origin = dev.hermeskotlin.core.wear.CallOrigin(gateway, "work", "rt9", "bound", method)
            val call = FakeCall()
            val job = backgroundScope.launch { LiveConversation(call, session, MutableStateFlow(VoiceChatState()), subscriptionProtocol = SubscriptionLiveProtocol(), incoming = IncomingVoiceRequest(origin, "stored-9", request)).run {} }
            call.receive("""{"type":"session.started"}""")
            call.sent.first { it.any { msg -> msg["type"]?.jsonPrimitive?.contentOrNull == "session.context.append" } }
            call.receive("""{"type":"output_transcript.added","item":{"text":"${if (method == "approval") "Should I run example, yes or no?" else "Color?"}"}}""")
            val partial = if (method == "approval") "yes" else "Red"
            val corrected = if (method == "approval") "no" else "Blue"
            call.receive("""{"type":"input_transcript.added","item":{"text":"$partial"}}""")
            advanceTimeBy(10_000); runCurrent()
            assertTrue(socket.answers.value.isEmpty(), "partial $method answered")
            call.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"$corrected"}}""")
            advanceTimeBy(10_000); runCurrent()
            val answer = socket.answers.value.single()
            assertEquals("bound", answer["id"]!!.jsonPrimitive.content)
            assertEquals(if (method == "approval") "deny" else "Blue", answer["result"]!!.jsonObject[if (method == "approval") "choice" else "answer"]!!.jsonPrimitive.content)
            call.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"yes"}}""")
            call.receive("""{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"d","content":[]}}""")
            runCurrent()
            assertEquals(1, socket.answers.value.size)
            job.cancel()
        }
    }

    @Test
    fun incomingApprovalRechecksLockAfterResumeSuspends() = runTest {
        val client = http("gpt-live", mutableListOf())
        val socket = Socket().apply { resume = """{"session_id":"rt9","running":true,"open_requests":[{"id":"bound","method":"approval","params":{"session_id":"rt9","command":"example","choices":["once","deny"]}}]}""" }
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway); connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, "stored-9", null, connection, SessionsApi(client), backgroundScope, profile = "work")
        session.start()
        val request = session.state.first { it.inputRequests.isNotEmpty() }.inputRequests.single()
        val origin = dev.hermeskotlin.core.wear.CallOrigin(gateway, "work", "rt9", "bound", "approval")
        var locked = false
        val call = FakeCall()
        val job = backgroundScope.launch { LiveConversation(call, session, MutableStateFlow(VoiceChatState()), lock = DeviceLock { locked }, incoming = IncomingVoiceRequest(origin, "stored-9", request)).run {} }
        call.receive("""{"type":"session.started"}""")
        call.sent.first { it.any { msg -> msg["content"]?.jsonPrimitive?.contentOrNull?.contains("example") == true } }
        socket.resumeGate = CompletableDeferred()
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yes","start_ms":2000,"end_ms":3000}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        socket.resumes.first { it.size >= 2 }
        locked = true
        socket.resumeGate!!.complete(Unit)
        runCurrent()
        assertTrue(socket.answers.value.isEmpty())
        assertTrue(session.state.value.inputRequests.any { it.id == "bound" })
        job.cancel()
    }

    @Test
    fun incomingSpokenDenialRemovesRequestWithoutFalseRejection() = runTest {
        val client = http("gpt-live", mutableListOf())
        val socket = Socket().apply { resume = """{"session_id":"rt9","running":true,"open_requests":[{"id":"bound","method":"approval","params":{"session_id":"rt9","command":"example","description":"Example operation","choices":["once","session","deny"]}}]}""" }
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, "stored-9", null, connection, SessionsApi(client), backgroundScope, profile = "work")
        session.start()
        val request = session.state.first { it.inputRequests.isNotEmpty() }.inputRequests.single()
        val origin = dev.hermeskotlin.core.wear.CallOrigin(gateway, "work", "rt9", "bound", "approval")
        val call = FakeCall()
        val voiceState = MutableStateFlow(VoiceChatState())
        val conversation = LiveConversation(call, session, voiceState, incoming = IncomingVoiceRequest(origin, "stored-9", request))
        val result = backgroundScope.async { conversation.run {} }
        call.receive("""{"type":"session.started","session":{"id":"live-1"}}""")
        call.sent.first { it.any { msg -> msg["content"]?.jsonPrimitive?.contentOrNull?.contains("Example operation") == true } }
        assertTrue(socket.answers.value.isEmpty())
        call.receive("""{"type":"session.input_transcript.delta","delta":"No","start_ms":2000,"end_ms":3000}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("bound", answer["id"]!!.jsonPrimitive.content)
        assertEquals("deny", answer["result"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        session.state.first { it.inputRequests.isEmpty() }
        runCurrent()
        assertFalse(result.isCompleted)
        call.close()
        assertEquals("The live call dropped.", result.await())
        assertTrue(voiceState.value.error?.contains("no longer verified") != true)
        assertEquals(1, socket.answers.value.size)
        assertTrue(socket.submits.value.isEmpty())
    }

    @Test
    fun incomingCallAnswersItsBoundRequestNotTheFirstPendingRequest() = runTest {
        val client = http("gpt-live", mutableListOf())
        val socket = Socket().apply { resume = """{"session_id":"rt9","running":true,"open_requests":[{"id":"first","method":"clarify","params":{"session_id":"rt9","question":"First?"}},{"id":"bound","method":"clarify","params":{"session_id":"rt9","question":"Bound?","choices":["Red","Blue"]}}]}""" }
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, "stored-9", null, connection, SessionsApi(client), backgroundScope, profile = "work")
        session.start()
        val pending = session.state.first { it.inputRequests.size == 2 }.inputRequests
        val bound = pending.single { it.id == "bound" }
        val origin = dev.hermeskotlin.core.wear.CallOrigin(gateway, "work", "rt9", "bound", "clarify")
        val call = FakeCall()
        val conversation = LiveConversation(call, session, MutableStateFlow(VoiceChatState()), incoming = IncomingVoiceRequest(origin, "stored-9", bound))
        val job = backgroundScope.launch { conversation.run {} }
        call.receive("""{"type":"session.started","session":{"id":"live-1"}}""")
        call.sent.first { it.any { msg -> msg["content"]?.jsonPrimitive?.contentOrNull?.contains("Bound?") == true } }
        assertTrue(call.said("session.commentary.append").none { it.contains("First?") })
        call.receive("""{"type":"session.input_transcript.delta","delta":"Blue","start_ms":2000,"end_ms":3000}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("bound", answer["id"]!!.jsonPrimitive.content)
        assertEquals("Blue", answer["result"]!!.jsonObject["answer"]!!.jsonPrimitive.content)
        assertTrue(socket.resumes.value.size >= 2)
        val params = socket.resumes.value.last()["params"]!!.jsonObject
        assertEquals("stored-9", params["session_id"]!!.jsonPrimitive.content)
        assertEquals("work", params["profile"]!!.jsonPrimitive.content)
        assertTrue(socket.submits.value.isEmpty())
        job.cancel()
    }

    @Test
    fun anApprovalHermesStopsOnIsAskedByTheVoiceAndTheSpokenAnswerGoesBack() = runTest {
        val (socket, session, call, voice) = callWithATurn()

        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","description":"Delete the build folder","choices":["once","session","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("Delete the build folder") == true } }
        assertEquals(1, call.said("session.instructions.append").size)
        assertEquals(
            "Hermes needs an answer before it goes on. Hermes needs your approval: Delete the build folder. Say yes, for this chat, or no.",
            call.said("session.commentary.append").last(),
        )

        // The person's own words answer it; the voice's request isn't a new turn and doesn't stop the waiting one.
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yes, for this chat.","start_ms":2000,"end_ms":3000}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("srq-1", answer["id"]!!.jsonPrimitive.content)
        assertEquals("session", answer["result"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        assertEquals(1, socket.submits.value.size)
        assertTrue(session.state.value.running)

        // The turn carries on, and its reply still goes to the request that started it.
        socket.event("message.complete", """{"text":"Deleted.","status":"complete"}""")
        call.sent.first { sent -> sent.any { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d1" && it["content"]?.jsonPrimitive?.contentOrNull == "Deleted." } }
        voice.stopChat()
    }

    @Test
    fun aLoneQuestionSentAsABatchIsAskedAndAnsweredUnderItsId() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-3","method":"clarify","params":{"session_id":"rt9","questions":[{"qid":"q1","question":"Which color?","choices":["Red","Blue"]}]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("Which color?") == true } }

        call.receive("""{"type":"session.input_transcript.delta","delta":"Blue please","start_ms":2000,"end_ms":3000}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("Blue", answer["result"]!!.jsonObject["answers"]!!.jsonObject["q1"]!!.jsonPrimitive.content)
        voice.stopChat()
    }

    @Test
    fun anApprovalIsntTakenFromALockedPhone() = runTest {
        var locked = true
        val (socket, session, call, voice) = callWithATurn { locked }
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("Unlock the phone") == true } }
        assertTrue(call.said("session.commentary.append").none { it.contains("rm -rf build") })

        // Unlocked, it's asked.
        locked = false
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        assertTrue(session.state.value.running)
        voice.stopChat()
    }

    @Test
    fun aHandOverAfterTheAnswerDoesntStopTheTurn() = runTest {
        val (socket, session, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yes.","start_ms":2000,"end_ms":3000}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        socket.answers.first { it.isNotEmpty() }
        // The voice hands the same answer over again: not a new request.
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d3"}}""")
        call.sent.first { sent -> sent.any { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d3" } }
        assertEquals(1, socket.submits.value.size)
        assertEquals(1, socket.answers.value.size)
        assertTrue(session.state.value.running)
        voice.stopChat()
    }

    @Test
    fun wordsBeforeTheVoiceAskedBackArentTheAnswer() = runTest {
        val (socket, session, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yes.","start_ms":2000,"end_ms":2500}""")
        call.receive("""{"type":"session.output_transcript.delta","delta":"Are you sure?","start_ms":2600,"end_ms":3000}""")
        call.receive("""{"type":"session.input_transcript.delta","delta":"No, don't.","start_ms":3200,"end_ms":3800}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("deny", answer["result"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        assertTrue(session.state.value.running)
        voice.stopChat()
    }

    @Test
    fun theVoiceRespondingDoesntCutTheAnswerShort() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        // The person's transcript lags: the voice's "Okay" comes in before the rest of what they said before it.
        call.receive("""{"type":"session.input_transcript.delta","delta":"No, don't","start_ms":2000,"end_ms":2400}""")
        call.receive("""{"type":"session.output_transcript.delta","delta":"Okay.","start_ms":3000,"end_ms":3300}""")
        call.receive("""{"type":"session.input_transcript.delta","delta":" do it.","start_ms":2400,"end_ms":2700}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("deny", answer["result"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        voice.stopChat()
    }

    @Test
    fun wordsFromBeforeTheQuestionComingInLateArentTheAnswer() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        // Said before the voice asked (to something else), and only now written down.
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yes, go ahead.","start_ms":400,"end_ms":900}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        call.sent.first { sent -> sent.any { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d2" } }
        assertTrue(socket.answers.value.isEmpty())
        voice.stopChat()
    }

    @Test
    fun withoutTimesTheAnswerAndANewRequestAreStillTold() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        call.spokenAt = null
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yes."}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("once", answer["result"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        // The same answer handed over again isn't a request; new words after it are.
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d3"}}""")
        call.sent.first { sent -> sent.any { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d3" } }
        assertEquals(1, socket.submits.value.size)
        call.receive("""{"type":"session.input_transcript.delta","delta":"Now check the disk space."}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d4"}}""")
        socket.submits.first { it.size == 2 }
        voice.stopChat()
    }

    @Test
    fun anAnswerIsTakenOnceThePersonStops() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yes","start_ms":2000,"end_ms":2300}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        delay(500)
        call.receive("""{"type":"session.input_transcript.delta","delta":", but first tell me what it does.","start_ms":2300,"end_ms":3000}""")
        call.sent.first { sent -> sent.any { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d2" } }
        assertEquals(
            "That wasn't clear. Hermes needs your approval: rm -rf build. Say yes, or no.",
            call.sent.value.single { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d2" }["content"]!!.jsonPrimitive.content,
        )
        assertTrue(socket.answers.value.isEmpty())
        voice.stopChat()
    }

    @Test
    fun aSecondHandOverWhileTheAnswerGoesIsntASecondAnswer() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yes.","start_ms":2000,"end_ms":2500}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d3"}}""")
        call.sent.first { sent -> sent.any { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d3" } }
        socket.answers.first { it.isNotEmpty() }
        delay(5_000)
        assertEquals(1, socket.answers.value.size)
        assertEquals(1, socket.submits.value.size)
        voice.stopChat()
    }

    @Test
    fun aNewRequestRightAfterTheAnswerIsStillOne() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yes.","start_ms":2000,"end_ms":2500}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        socket.answers.first { it.isNotEmpty() }
        // Handed over before the new words' transcript comes in.
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d3"}}""")
        delay(300)
        call.receive("""{"type":"session.input_transcript.delta","delta":"Now check the disk space.","start_ms":6000,"end_ms":7000}""")
        socket.submits.first { it.size == 2 }
        assertTrue(socket.submits.value.last().toString().contains("check the disk space"))
        voice.stopChat()
    }

    @Test
    fun anUnclearAnswerIsAskedAgainAndNothingIsSent() = runTest {
        val (socket, session, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        session.state.first { it.inputRequests.isNotEmpty() }

        call.receive("""{"type":"session.input_transcript.delta","delta":"What does it delete?","start_ms":2000,"end_ms":3000}""")
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        call.sent.first { sent -> sent.any { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d2" } }
        assertEquals(
            "That wasn't clear. Hermes needs your approval: rm -rf build. Say yes, or no.",
            call.sent.value.single { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d2" }["content"]!!.jsonPrimitive.content,
        )
        assertTrue(socket.answers.value.isEmpty())
        assertEquals(1, socket.submits.value.size)
        voice.stopChat()
    }

    @Test
    fun anAnswerTheVoiceNeverHandsOverIsTakenOnceThePersonStops() = runTest {
        val (socket, session, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-3","method":"clarify","params":{"session_id":"rt9","questions":[{"qid":"q1","question":"Which color?","choices":["Red","Blue"]}]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("Which color?") == true } }

        // The voice just talks on ("Green, nice!") instead of handing the answer over.
        call.receive("""{"type":"session.input_transcript.delta","delta":"Green.","start_ms":2000,"end_ms":2600}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("Green.", answer["result"]!!.jsonObject["answers"]!!.jsonObject["q1"]!!.jsonPrimitive.content)
        assertEquals(1, socket.submits.value.size)
        assertTrue(session.state.value.running)

        // Handed over late after all: not a second answer, nor a request that stops the turn. Told so while Hermes
        // still works on it, not once its turn is over.
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        val reply = call.sent.first { sent -> sent.any { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d2" } }
            .first { it["delegation_id"]?.jsonPrimitive?.contentOrNull == "d2" }
        assertTrue(reply.toString().contains("already has that answer"), reply.toString())
        assertTrue(session.state.value.running)
        delay(5_000)
        assertEquals(1, socket.answers.value.size)
        assertEquals(1, socket.submits.value.size)
        voice.stopChat()
    }

    @Test
    fun aQuestionBackIsLeftToTheVoice() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-3","method":"clarify","params":{"session_id":"rt9","questions":[{"qid":"q1","question":"Which color?","choices":["Red","Blue"]}]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("Which color?") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Which one is brighter","start_ms":2000,"end_ms":2600}""")
        delay(10_000)
        assertTrue(socket.answers.value.isEmpty())
        assertEquals(1, socket.submits.value.size)
        voice.stopChat()
    }

    @Test
    fun anApprovalIsTakenWithoutAHandOverOnlyWhenItsClear() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Okay, let me think","start_ms":2000,"end_ms":2600}""")
        delay(5_000)
        assertTrue(socket.answers.value.isEmpty())
        call.receive("""{"type":"session.input_transcript.delta","delta":"No, don't.","start_ms":9000,"end_ms":9600}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("deny", answer["result"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        voice.stopChat()
    }

    @Test
    fun aYesBeforeTheVoiceAskedIsLeftToTheVoice() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        // The end of what the voice said before it was given the approval: it hasn't asked yet.
        call.asks = "Sure, I'll clean that up now."
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yeah.","start_ms":2000,"end_ms":2400}""")
        delay(10_000)
        assertTrue(socket.answers.value.isEmpty())
        // The voice hands it over once it asked: then it's the answer.
        call.receive("""{"type":"session.delegation.created","delegation":{"id":"d2"}}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("once", answer["result"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        voice.stopChat()
    }

    @Test
    fun aYesToAQuestionLeftOverFromBeforeIsntTheApproval() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        // A question of the voice's own, from before it was given the approval.
        call.asks = "On it. Want me to keep you posted?"
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Yeah.","start_ms":2000,"end_ms":2400}""")
        delay(10_000)
        assertTrue(socket.answers.value.isEmpty())
        voice.stopChat()
    }

    @Test
    fun aLoneOkayToAnApprovalIsLeftToTheVoice() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Okay.","start_ms":2000,"end_ms":2400}""")
        delay(10_000)
        assertTrue(socket.answers.value.isEmpty())
        voice.stopChat()
    }

    @Test
    fun aYesAfterAnUmIsTakenAsTheYes() = runTest {
        val (socket, _, call, voice) = callWithATurn()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")
        call.sent.first { sent -> sent.any { it["content"]?.jsonPrimitive?.contentOrNull?.contains("rm -rf build") == true } }
        call.receive("""{"type":"session.input_transcript.delta","delta":"Um, yes.","start_ms":2000,"end_ms":2600}""")
        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals("once", answer["result"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        voice.stopChat()
    }

    /** Hears each of [said] in turn, then nothing more. [onRecord] is told as each starts. */
    private class Saying(vararg said: String, val onRecord: () -> Unit = {}) : VoiceRecorder {
        val left = ArrayDeque(said.toList())

        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            onRecord()
            if (left.isEmpty()) awaitCancellation()
            return Recording(left.first().encodeToByteArray(), "audio/wav", heardSpeech = true)
        }

        override fun finish() = Unit
    }

    @Test
    fun aQuestionInAChainedVoiceChatIsReadAloudAndTheAnswerSaidBackIsSent() = runTest {
        val recorder = Saying("Tidy the repo", "the second one")
        val spoken = mutableListOf<String>()
        val (socket, voice) = chainedTurn(recorder, spoken)
        socket.push("""{"jsonrpc":"2.0","id":"srq-2","method":"clarify","params":{"session_id":"rt9","question":"Which folder?","choices":["src","build"]}}""")

        val answer = socket.answers.first { it.isNotEmpty() }.single()
        assertEquals(listOf("Which folder? Say one. 1: src. 2: build. Or say something else."), spoken)
        assertEquals("srq-2", answer["id"]!!.jsonPrimitive.content)
        assertEquals("build", answer["result"]!!.jsonObject["answer"]!!.jsonPrimitive.content)
        // Answering isn't a new prompt.
        assertEquals(1, socket.submits.value.size)

        socket.event("message.complete", """{"text":"Tidied build.","status":"complete"}""")
        voice.chat.first { it.phase == VoicePhase.Listening && spoken.size == 2 }
        assertEquals("Tidied build.", spoken.last())
        voice.stopChat()
    }

    @Test
    fun aYesSaidOnceThePhoneLockedIsntTaken() = runTest {
        var locked = false
        lateinit var recorder: Saying
        // The phone locks while the answer is said.
        recorder = Saying("Tidy the repo", "yes", onRecord = { if (recorder.left.size == 1) locked = true })
        val spoken = mutableListOf<String>()
        val (socket, voice) = chainedTurn(recorder, spoken) { locked }
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","choices":["once","deny"]}}""")

        voice.chat.first { spoken.any { it.contains("Unlock the phone") } }
        assertTrue(socket.answers.value.isEmpty())
        voice.stopChat()
    }

    /** A chained voice chat whose first request, said aloud, is a turn now running. */
    private suspend fun TestScope.chainedTurn(
        recorder: Saying,
        spoken: MutableList<String>,
        lock: DeviceLock = DeviceLock.None,
    ): Pair<Socket, VoiceController> {
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"chained","available":false}""", HttpStatusCode.OK, json)
                    "/api/audio/transcribe" -> respond("""{"transcript":"${recorder.left.removeFirst()}"}""", HttpStatusCode.OK, json)
                    "/api/audio/speak" -> {
                        spoken += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["text"]!!.jsonPrimitive.content
                        respond("""{"data_url":"data:audio/mpeg;base64,AAAA"}""", HttpStatusCode.OK, json)
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
        val voice = VoiceController(AudioApi(client), recorder, Silent, backgroundScope, backgroundScope, deviceLock = lock) { FakeCall() }
        voice.startChat(session, gateway, null)

        socket.submits.first { it.size == 1 }
        socket.event("message.start")
        session.state.first { it.running }
        return socket to voice
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

package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.phone.CallReason
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.DeviceLock
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
import kotlinx.coroutines.delay
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

        /** What the phone answered the gateway's own requests (an approval, a question) with. */
        val answers = MutableStateFlow<List<JsonObject>>(emptyList())

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["method"] == null && message["result"] != null) return answers.update { it + message }
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
            val content = sent["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
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

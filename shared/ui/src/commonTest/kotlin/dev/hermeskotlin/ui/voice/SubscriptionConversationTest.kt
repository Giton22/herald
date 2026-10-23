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
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.SubscriptionVoiceOptions
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionConversationTest {

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

        /** The voice is making sound, as the phone hears its own speaker. */
        var speaking = false

        override fun speaking() = speaking
        override fun micLevel() = 0f
        override fun setMuted(muted: Boolean) = Unit

        override fun close() {
            closed = true
            incoming.close()
        }

        fun receive(event: String) {
            incoming.trySend(event)
        }

        /** What the voice was told about [itemId], in order. */
        fun told(itemId: String) = sent.value
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "delegation.context.append" && it["delegation_item_id"]?.jsonPrimitive?.contentOrNull == itemId }
            .map { it["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content }
    }

    /** Listens until the chat ends: the chained chat, where it's the fallback, waits on it. */
    private object NoRecorder : VoiceRecorder {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording = awaitCancellation()

        override fun finish() = Unit
    }

    private object Silent : SpeechPlayer {
        override suspend fun play(audio: SpokenAudio) = Unit
    }

    /**
     * The gateway's HTTP side: the plugin signed in as [codexOauth]; what the phone sent each route is kept. [refusal]:
     * the plugin can't start the call, and says this.
     */
    private class Gateway(val codexOauth: String, val refusal: String? = null) {
        val bodies = MutableStateFlow<Map<String, List<String>>>(emptyMap())

        fun sent(path: String) = bodies.value[path].orEmpty()
    }

    private fun http(gw: Gateway) = createHttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            gw.bodies.update { it + (path to (it[path].orEmpty() + request.body.toByteArray().decodeToString())) }
            when (path) {
                "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                "/api/plugins/talk-desktop/codex/status" -> respond("""{"ok":true,"configured":true,"codexOauth":"${gw.codexOauth}"}""", HttpStatusCode.OK, json)
                "/api/plugins/talk-desktop/codexlive/session" -> if (gw.refusal != null) {
                    respond("""{"detail":"${gw.refusal}"}""", HttpStatusCode.BadGateway, json)
                } else {
                    respond("""{"ok":true,"engine":"codex","answer":"v=0 answer\r\n","threadId":"th-1","handoff":"client"}""", HttpStatusCode.OK, json)
                }
                "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"chained","available":false}""", HttpStatusCode.OK, json)
                else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
            }
        },
        PersistentCookiesStorage(InMemoryKeyValueStore()),
    )

    private class Call(val voice: VoiceController, val call: FakeCall, val socket: Socket, val gateway: Gateway)

    private suspend fun TestScope.subscriptionCall(
        codexOauth: String = "valid",
        options: SubscriptionVoiceOptions = SubscriptionVoiceOptions(),
        profile: String? = "default",
        refusal: String? = null,
    ): Call {
        val gw = Gateway(codexOauth, refusal)
        val client = http(gw)
        val socket = Socket()
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val call = FakeCall()
        val voice = VoiceController(AudioApi(client), NoRecorder, Silent, backgroundScope, backgroundScope) { call }
        voice.startChat(session, gateway, profile, subscription = options)
        return Call(voice, call, socket, gw)
    }

    @Test
    fun aHandoverIsAHermesTurnAndItsReplyGoesBackToTheVoice() = runTest {
        val c = subscriptionCall()
        val voice = c.voice
        val call = c.call
        val socket = c.socket
        val gw = c.gateway
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        // The plugin traded the phone's offer for the vendor's answer; the default profile is the gateway's own persona.
        assertEquals("v=0 answer\r\n", call.answer)
        val offer = Json.parseToJsonElement(gw.sent("/api/plugins/talk-desktop/codexlive/session").single()).jsonObject
        assertEquals("v=0 offer\r\n", offer["offer"]!!.jsonPrimitive.content)
        assertEquals("en", offer["language"]!!.jsonPrimitive.content)
        assertFalse("profile" in offer)

        call.receive("""{"type":"input_transcript.added","item":{"text":"How full is"}}""")
        call.receive("""{"type":"input_transcript.added","item":{"text":" the disk?"}}""")
        voice.chat.first { it.caption == "How full is the disk?" && !it.captionIsVoice }
        call.receive("""{"type":"turn.done","turn":{"role":"user","id":"u1","transcript":"How full is the disk?"}}""")
        call.receive("""{"type":"delegation.created","item":{"id":"d1","content":[{"type":"input_text","text":"Check how full the disk is"}]}}""")

        val submit = socket.submits.first { it.isNotEmpty() }.single()["params"]!!.jsonObject
        assertEquals("Check how full the disk is", submit["text"]!!.jsonPrimitive.content)
        assertEquals("voice-live", submit["surface"]!!.jsonPrimitive.content)
        assertEquals("User: How full is the disk?", submit["voice_context"]!!.jsonPrimitive.content)
        voice.chat.first { it.phase == VoicePhase.Thinking }

        socket.event("message.start")
        socket.event("message.delta", """{"text":"The disk is **40%** full."}""")
        socket.event("message.complete", """{"text":"The disk is **40%** full.","status":"complete"}""")
        call.sent.first { call.told("d1").isNotEmpty() }
        assertEquals(listOf("Agent result for this request:\nThe disk is 40% full."), call.told("d1"))
        voice.chat.first { it.phase == VoicePhase.Listening }

        voice.stopChat()
        voice.chat.first { it.phase == VoicePhase.Off }
        assertTrue(call.closed)
        // Hung up on the gateway too, or the plugin refuses the next call.
        gw.bodies.first { it["/api/plugins/talk-desktop/codexlive/stop"].orEmpty().isNotEmpty() }
        assertEquals("th-1", Json.parseToJsonElement(gw.sent("/api/plugins/talk-desktop/codexlive/stop").single()).jsonObject["threadId"]!!.jsonPrimitive.content)
    }

    @Test
    fun aHandoverWithoutWordsTakesWhatThePersonSaidLast() = runTest {
        val c = subscriptionCall()
        c.voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        c.call.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"Restart the web server"}}""")
        c.call.receive("""{"type":"delegation.created","item":{"id":"d2","content":[]}}""")
        val submit = c.socket.submits.first { it.isNotEmpty() }.single()["params"]!!.jsonObject
        assertEquals("Restart the web server", submit["text"]!!.jsonPrimitive.content)
        c.voice.stopChat()
    }

    @Test
    fun sayingStopEndsTheCall() = runTest {
        val c = subscriptionCall()
        c.voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        c.call.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"Stop."}}""")
        c.voice.chat.first { it.phase == VoicePhase.Off }
        assertTrue(c.call.closed)
        assertEquals(null, c.voice.chat.value.error)
        assertTrue(c.socket.submits.value.isEmpty())
    }

    @Test
    fun thePickedVoiceAndPersonaGoToThePlugin() = runTest {
        val c = subscriptionCall(options = SubscriptionVoiceOptions(voice = "maple", persona = SubscriptionVoiceOptions.PERSONA_MAIN), profile = "work")
        c.voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        val sent = Json.parseToJsonElement(c.gateway.sent("/api/plugins/talk-desktop/codexlive/session").single()).jsonObject
        assertEquals("maple", sent["voice"]!!.jsonPrimitive.content)
        // The call says whose voice it is.
        assertEquals("ChatGPT live voice · Maple", c.voice.chat.value.via)
        assertEquals("ChatGPT live voice · Maple · say “stop” to end", hint(c.voice.chat.value))
        // The main bot's persona, whatever the chat's profile.
        assertFalse("profile" in sent)
        c.voice.stopChat()
    }

    @Test
    fun theChatsProfileIsThePersonaByDefault() = runTest {
        val c = subscriptionCall(profile = "work")
        c.voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        val sent = Json.parseToJsonElement(c.gateway.sent("/api/plugins/talk-desktop/codexlive/session").single()).jsonObject
        assertEquals("work", sent["profile"]!!.jsonPrimitive.content)
        // The plugin's own voice when none is picked.
        assertFalse("voice" in sent)
        c.voice.stopChat()
    }

    @Test
    fun withWorkOffTheVoiceIsToldAndNothingRuns() = runTest {
        val c = subscriptionCall(options = SubscriptionVoiceOptions(chatWork = false))
        c.voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        c.call.receive("""{"type":"delegation.created","item":{"id":"d3","content":[{"type":"input_text","text":"Delete the old logs"}]}}""")
        c.call.sent.first { c.call.told("d3").isNotEmpty() }
        assertEquals(listOf("Work in the chat is turned off. No task was submitted."), c.call.told("d3"))
        assertTrue(c.socket.submits.value.isEmpty())
        c.voice.stopChat()
    }

    @Test
    fun aCallThatEndsIsCountedOnThePlugin() = runTest {
        val c = subscriptionCall()
        c.voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        c.call.receive("""{"type":"session.usage.updated","usage":{"audio_duration_ms":4200}}""")
        c.call.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"Stop."}}""")
        c.gateway.bodies.first { it["/api/plugins/talk-desktop/voice/usage/report"].orEmpty().isNotEmpty() }
        val report = Json.parseToJsonElement(c.gateway.sent("/api/plugins/talk-desktop/voice/usage/report").single()).jsonObject
        assertEquals(4200, report["audioMs"]!!.jsonPrimitive.content.toInt())
        assertTrue(report["durationMs"]!!.jsonPrimitive.content.toLong() >= 1)
    }

    @Test
    fun talkingOverTheVoiceCutsItsTurnShort() = runTest {
        val c = subscriptionCall()
        c.voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        c.call.receive("""{"type":"turn.created","turn":{"role":"assistant","id":"t9"}}""")
        c.call.speaking = true
        c.voice.chat.first { it.phase == VoicePhase.Speaking }
        // Past the voice's first moment.
        delay(1_000)
        c.call.receive("""{"type":"input_transcript.added","item":{"text":"Hold on a"}}""")
        c.gateway.bodies.first { it["/api/plugins/talk-desktop/codexlive/interrupt"].orEmpty().isNotEmpty() }
        val cut = Json.parseToJsonElement(c.gateway.sent("/api/plugins/talk-desktop/codexlive/interrupt").single()).jsonObject
        assertEquals("t9", cut["turnId"]!!.jsonPrimitive.content)
        assertEquals("th-1", cut["threadId"]!!.jsonPrimitive.content)
        // Once per turn.
        c.call.receive("""{"type":"input_transcript.added","item":{"text":" second please"}}""")
        delay(500)
        assertEquals(1, c.gateway.sent("/api/plugins/talk-desktop/codexlive/interrupt").size)
        c.voice.stopChat()
    }

    @Test
    fun aWordAsTheVoiceStartsOrAloneDoesNotCutIn() = runTest {
        val c = subscriptionCall()
        c.voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        c.call.receive("""{"type":"turn.created","turn":{"role":"assistant","id":"t9"}}""")
        c.call.speaking = true
        c.voice.chat.first { it.phase == VoicePhase.Speaking }
        // The voice's first moment: not yet.
        c.call.receive("""{"type":"input_transcript.added","item":{"text":"Hold on a second"}}""")
        delay(1_000)
        c.call.speaking = false
        c.voice.chat.first { it.phase == VoicePhase.Listening }
        c.call.speaking = true
        delay(1_000)
        // A lone "mm" over the voice isn't someone cutting in.
        c.call.receive("""{"type":"input_transcript.added","item":{"text":"mm"}}""")
        delay(500)
        assertTrue(c.gateway.sent("/api/plugins/talk-desktop/codexlive/interrupt").isEmpty())
        c.voice.stopChat()
    }

    @Test
    fun aPlanOutOfLiveVoiceFallsBackToTheProfilesVoiceChat() = runTest {
        val c = subscriptionCall(refusal = "LIVE_SIN_QUOTA: weekly limit reached")
        // The chained chat listens, and says why it's that one.
        val state = c.voice.chat.first { it.phase == VoicePhase.Listening && !it.live && it.error != null }
        assertEquals("The ChatGPT plan is out of live voice for now. This is the profile's own voice chat instead.", state.error)
        // Nothing to hang up or count: the call never came up.
        assertTrue(c.gateway.sent("/api/plugins/talk-desktop/codexlive/stop").isEmpty())
        assertTrue(c.gateway.sent("/api/plugins/talk-desktop/voice/usage/report").isEmpty())
        c.voice.stopChat()
    }

    @Test
    fun withoutAChatGptSignInTheVoiceChatIsTheProfilesOwn() = runTest {
        val c = subscriptionCall(codexOauth = "missing")
        // The chained chat listens; no live call was made.
        c.voice.chat.first { it.phase == VoicePhase.Listening && !it.live }
        assertTrue(c.gateway.sent("/api/plugins/talk-desktop/codexlive/session").isEmpty())
        assertEquals(null, c.call.answer)
        c.voice.stopChat()
    }
}

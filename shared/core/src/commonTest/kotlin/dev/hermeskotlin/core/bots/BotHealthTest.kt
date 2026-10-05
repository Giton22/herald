package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.DeliveryOutcome
import dev.hermeskotlin.core.chat.TranscriptEvent
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotHealthTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** The stored transcript every chat opens with. */
    private var history = """{"session_id":"stored-1","messages":[]}"""

    private fun client() = createHttpClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                else -> respond(history, HttpStatusCode.OK, json)
            }
        },
    )

    private fun CoroutineScope.serve(transport: FakeTransport) = launch {
        var answered = 0
        transport.sent.collect { sent ->
            sent.drop(answered).forEach { message ->
                answered++
                val id = message["id"] ?: return@forEach
                val method = message["method"]?.jsonPrimitive?.contentOrNull
                val result = if (method == "session.resume") """{"session_id":"rt1","running":false}""" else "{}"
                transport.push("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            }
        }
    }

    private class Setup(val host: ChatHost, val health: BotHealth, val transport: FakeTransport, val checks: MutableList<String>)

    private suspend fun setup(scope: CoroutineScope, answer: (String) -> RuntimeCheck = { RuntimeCheck(ok = true) }): Setup {
        val http = client()
        val transport = FakeTransport()
        val connection = GatewayConnection(AuthApi(http, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> transport }, scope)
        scope.serve(transport)
        connection.start(url)
        transport.push(FakeTransport.READY)
        connection.state.first { it is ConnectionState.Connected }
        val host = ChatHost(connection, SessionsApi(http), scope)
        val checks = mutableListOf<String>()
        val health = BotHealth(connection, host, scope) { profile -> checks += profile; answer(profile) }
        return Setup(host, health, transport, checks)
    }

    private fun event(type: String, payload: String = "{}") =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt1","payload":$payload}}"""

    @Test
    fun lastingFailuresAreToldApartFromPassingOnes() {
        assertEquals(BotProblem.SignIn, BotProblem.classify("provider_auth_or_access"))
        assertEquals(BotProblem.Quota, BotProblem.classify("Error code: 402 - insufficient credits"))
        assertEquals(BotProblem.SignIn, BotProblem.classify("401 Unauthorized: invalid api key"))
        assertEquals(BotProblem.Setup, BotProblem.classify("No LLM provider configured"))
        assertEquals(BotProblem.Blocked, BotProblem.classify("agent_blocked"))
        // A rate limit or an outage passes on its own; never a badge, even worded like a quota.
        assertNull(BotProblem.classify("429 Too Many Requests: rate limit, quota resets soon"))
        assertNull(BotProblem.classify("503 Service Unavailable"))
        assertNull(BotProblem.classify("Request timed out"))
        assertNull(BotProblem.classify("something else went wrong"))
        assertNull(BotProblem.classify(null))
    }

    @Test
    fun eachBotIsCheckedOncePerConnection() = runTest {
        val s = setup(backgroundScope) { profile -> if (profile == "scribe") RuntimeCheck(false, "No API key found for openrouter") else RuntimeCheck(true) }

        s.health.checkOnce(listOf("scribe", "default"))
        s.health.checkOnce(listOf("scribe", "default"))

        val trouble = s.health.troubles.first { it.isNotEmpty() }["scribe"]
        assertEquals(BotProblem.Setup, trouble?.problem)
        assertEquals(BotTrouble.Source.Check, trouble?.source)
        assertEquals(listOf("scribe", "default"), s.checks)
    }

    @Test
    fun aCheckThatPassesClearsWhatACheckFound() = runTest {
        var ok = false
        val s = setup(backgroundScope) { RuntimeCheck(ok, "401 unauthorized") }
        s.health.recheck("scribe")
        s.health.troubles.first { "scribe" in it }

        ok = true
        s.health.recheck("scribe")

        s.health.troubles.first { it.isEmpty() }
    }

    @Test
    fun aGoodTurnClearsWhatAFailedOneSaid() = runTest {
        val s = setup(backgroundScope)
        s.health.noteFailure("scribe", "provider_quota_limit")
        assertEquals(BotProblem.Quota, s.health.troubles.value["scribe"]?.problem)

        s.health.noteAnswered("scribe")

        assertTrue(s.health.troubles.value.isEmpty())
    }

    @Test
    fun aBotChatsFailedTurnMarksTheBotAndItsNextReplyClearsIt() = runTest {
        val s = setup(backgroundScope)
        val chat = s.host.open(url, "stored-1", "Bot Chat", profile = "scribe")
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }

        s.transport.push(event("message.start"))
        s.transport.push(event("error", """{"message":"Error code: 401 - invalid api key"}"""))
        assertEquals(BotProblem.SignIn, s.health.troubles.first { "scribe" in it }["scribe"]?.problem)

        s.transport.push(event("message.start"))
        s.transport.push(event("message.delta", """{"text":"Back again."}"""))
        s.transport.push(event("message.complete", """{"text":"Back again."}"""))

        s.health.troubles.first { it.isEmpty() }
    }

    private val failedDelivery = """{"session_id":"stored-1","messages":[
        {"id":1,"role":"user","display_kind":"process_complete","content":"[IMPORTANT: Background process proc_1 exited (exit code 1).\nCommand: python3 /opt/hermes/tools/bot_mode_dm.py --run-delivery /opt/hermes/.venv/bin/hermes -p researcher chat -c 'Bot Chat' -Q\nOutput:\nDelivery to researcher failed [reason: provider_quota_limit]: out of credits]"}
    ]}"""

    @Test
    fun aDeliveryThatFailsWhileTheChatIsOpenMarksItsTarget() = runTest {
        val s = setup(backgroundScope)
        val chat = s.host.open(url, "stored-1", "Bot Chat", profile = "scribe")
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }

        // The delivery's result wakes a turn; the chat reads its transcript again when that turn ends.
        history = failedDelivery
        s.transport.push(event("message.start"))
        chat.state.first { it.running }
        s.transport.push(event("message.complete", """{"text":""}"""))

        val trouble = s.health.troubles.first { "researcher" in it }["researcher"]
        assertEquals(BotProblem.Quota, trouble?.problem)
        assertEquals(BotTrouble.Source.Turn, trouble?.source)
    }

    @Test
    fun aFailedDeliveryInTheTranscriptAtOpenIsHistoryNotNews() = runTest {
        history = failedDelivery
        val s = setup(backgroundScope)
        val chat = s.host.open(url, "stored-1", "Bot Chat", profile = "scribe")
        val opened = chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        // It is there to be read: a failed delivery to the researcher.
        val delivery = opened.messages.mapNotNull { (it as? ChatMessage.Event)?.event as? TranscriptEvent.Delivery }.single()
        assertEquals("researcher", delivery.target)
        assertIs<DeliveryOutcome.Failed>(delivery.outcome)

        // Something later in the chat, so the watcher has run over the opening transcript.
        s.transport.push(event("message.start"))
        chat.state.first { it.running }
        s.transport.push(event("message.complete", """{"text":"ok"}"""))
        chat.state.first { !it.running }

        assertTrue(s.health.troubles.value.isEmpty())
    }
}

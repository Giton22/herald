package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.DeliveryOutcome
import dev.hermeskotlin.core.chat.TranscriptEvent
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotHealthTest {

    private val url = FakeGateway.URL

    /** The stored transcript every chat opens with. */
    private var history = """{"session_id":"stored-1","messages":[]}"""

    /** Methods the fake gateway turns down, with the error message it sends. */
    private val refusals = mutableMapOf<String, String>()

    private class Setup(val host: ChatHost, val health: BotHealth, val transport: FakeTransport, val checks: MutableList<String>)

    private suspend fun setup(scope: CoroutineScope, answer: suspend (String) -> RuntimeCheck = { RuntimeCheck(ok = true) }): Setup {
        val gateway = FakeGateway(scope, reconnects = false).apply {
            http = { json(history) }
            this.answer = { call ->
                refusals[call.method]?.let { call.error(5000, it) }
                    ?: if (call.method == "session.resume") """{"session_id":"rt1","running":false}""" else "{}"
            }
        }
        val connection = gateway.start()
        val host = ChatHost(connection, SessionsApi(gateway.client), scope)
        val checks = mutableListOf<String>()
        val health = BotHealth(connection, host, scope) { profile -> checks += profile; answer(profile) }
        return Setup(host, health, gateway.socket, checks)
    }

    private fun rt1Event(type: String, payload: String = "{}") = event(type, sessionId = "rt1", payload = payload)

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
    fun aFailureOnThePhoneSaysNothingAboutTheBot() = runTest {
        val s = setup(backgroundScope)
        val chat = s.host.open(url, "stored-1", "Bot Chat", profile = "scribe")
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        refusals["prompt.submit"] = "403 Forbidden: not allowed from this client"

        // The send fails on the way, with words that would read as a refused key.
        chat.send("hello")
        chat.state.first { it.error != null }
        // Let the watcher see it (background work runs on runCurrent).
        testScheduler.runCurrent()

        assertTrue(s.health.troubles.value.isEmpty())
    }

    @Test
    fun aCheckBegunBeforeAResetDoesNotLandAfterIt() = runTest {
        val answer = CompletableDeferred<RuntimeCheck>()
        val s = setup(backgroundScope) { answer.await() }
        s.health.recheck("scout")
        // The check is under way (background work runs on runCurrent, not advanceUntilIdle).
        testScheduler.runCurrent()
        assertEquals(listOf("scout"), s.checks)

        // Another gateway: its bots are other bots, whatever the old one's check says.
        s.health.reset()
        answer.complete(RuntimeCheck(ok = false, error = "401 unauthorized"))
        testScheduler.runCurrent()

        assertTrue(s.health.troubles.value.isEmpty())
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
    fun aGoodTurnAsksAgainAboutWhatACheckFound() = runTest {
        var ok = false
        val s = setup(backgroundScope) { RuntimeCheck(ok, "No API key found for openrouter") }
        s.health.recheck("scribe")
        s.health.troubles.first { "scribe" in it }

        // Its key was fixed elsewhere, and it just answered.
        ok = true
        s.health.noteAnswered("scribe")

        s.health.troubles.first { it.isEmpty() }
        assertEquals(listOf("scribe", "scribe"), s.checks)
    }

    @Test
    fun aBotChatsFailedTurnMarksTheBotAndItsNextReplyClearsIt() = runTest {
        val s = setup(backgroundScope)
        val chat = s.host.open(url, "stored-1", "Bot Chat", profile = "scribe")
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        // The watcher listens for the gateway's events from here on; an event sent before it does isn't heard.
        testScheduler.runCurrent()

        s.transport.push(rt1Event("message.start"))
        s.transport.push(rt1Event("error", """{"message":"Error code: 401 - invalid api key"}"""))
        assertEquals(BotProblem.SignIn, s.health.troubles.first { "scribe" in it }["scribe"]?.problem)

        s.transport.push(rt1Event("message.start"))
        s.transport.push(rt1Event("message.delta", """{"text":"Back again."}"""))
        s.transport.push(rt1Event("message.complete", """{"text":"Back again."}"""))

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
        testScheduler.runCurrent()

        // The delivery's result wakes a turn; the chat reads its transcript again when that turn ends.
        history = failedDelivery
        s.transport.push(rt1Event("message.start"))
        chat.state.first { it.running }
        s.transport.push(rt1Event("message.complete", """{"text":""}"""))

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
        s.transport.push(rt1Event("message.start"))
        chat.state.first { it.running }
        s.transport.push(rt1Event("message.complete", """{"text":"ok"}"""))
        chat.state.first { !it.running }

        assertTrue(s.health.troubles.value.isEmpty())
    }
}

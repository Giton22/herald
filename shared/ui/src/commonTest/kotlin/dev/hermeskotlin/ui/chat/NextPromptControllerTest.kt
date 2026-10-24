package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.plugins.NextPromptApi
import dev.hermeskotlin.core.plugins.PluginsApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextPromptControllerTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** A gateway with the plugin (or not) whose suggestion route answers [answers] in turn, then the last one forever. */
    private class Gateway(var installed: Boolean = true, val answers: MutableList<String> = mutableListOf(NONE)) {
        val asked = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        var listed = 0
    }

    private fun TestScope.controller(gateway: Gateway, now: () -> Long = { testScheduler.currentTime }): NextPromptController {
        // On the test's scheduler, so a request finishes within runCurrent and virtual time.
        val engine = MockEngineConfig()
        engine.dispatcher = StandardTestDispatcher(testScheduler)
        engine.addHandler { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/api/dashboard/plugins") -> {
                    gateway.listed++
                    if (gateway.installed) """[{"name":"next-prompt","tab":{"path":"/next-prompt","hidden":true}}]""" else "[]"
                }
                path.endsWith("/suggestion") -> {
                    gateway.asked += request.url.parameters["session_id"].orEmpty()
                    if (gateway.answers.size > 1) gateway.answers.removeAt(0) else gateway.answers.single()
                }
                path.endsWith("/dismiss") -> {
                    gateway.dismissed += request.url.parameters["session_id"].orEmpty()
                    """{"ok":true}"""
                }
                else -> error("Unexpected $path")
            }
            if (body == NOT_SERVED) {
                respond("""{"detail":"No such API endpoint: /api/plugins/next-prompt/suggestion"}""", HttpStatusCode.NotFound, json)
            } else {
                respond(body, HttpStatusCode.OK, json)
            }
        }
        val client = createHttpClient(MockEngine(engine))
        return NextPromptController(NextPromptApi(client, PluginsApi(client)), backgroundScope, now)
    }

    private fun chat(id: String? = "s1", running: Boolean = false, loaded: Boolean = true) =
        ChatState(storedSessionId = id, running = running, historyLoaded = loaded)

    @Test
    fun openingAChatShowsTheSuggestionStillWaiting() = runTest {
        val gateway = Gateway(answers = mutableListOf(suggestion("Commit the parser fix")))
        val controller = controller(gateway)

        controller.follow(url, null, MutableStateFlow(chat()), MutableStateFlow(true))
        runCurrent()

        assertEquals(SuggestedReply("s1", "Commit the parser fix"), controller.suggestion.value)
        assertEquals(listOf("s1"), gateway.asked)
    }

    @Test
    fun openingAChatWithNoneAsksOnlyOnce() = runTest {
        val gateway = Gateway()
        val controller = controller(gateway)

        controller.follow(url, null, MutableStateFlow(chat()), MutableStateFlow(true))
        advanceTimeBy(120_000)

        assertNull(controller.suggestion.value)
        assertEquals(1, gateway.asked.size)
    }

    @Test
    fun afterATurnItAsksUntilTheSuggestionIsStored() = runTest {
        val gateway = Gateway()
        val controller = controller(gateway)
        val states = MutableStateFlow(chat(running = true))
        controller.follow(url, null, states, MutableStateFlow(true))
        runCurrent()
        assertTrue(gateway.asked.isEmpty(), "Nothing is asked while the turn runs")

        gateway.answers.apply { clear(); addAll(listOf(NONE, NONE, suggestion("Deploy to staging first"))) }
        states.value = chat(running = false)
        advanceTimeBy(2_500)
        assertNull(controller.suggestion.value)
        advanceTimeBy(1_000)

        assertEquals(SuggestedReply("s1", "Deploy to staging first"), controller.suggestion.value)
        advanceTimeBy(120_000)
        assertEquals(3, gateway.asked.size, "Found: no more asking")
    }

    @Test
    fun afterATurnItGivesUpAfterAMinute() = runTest {
        val gateway = Gateway()
        val controller = controller(gateway)
        val states = MutableStateFlow(chat(running = true))
        controller.follow(url, null, states, MutableStateFlow(true))
        runCurrent()

        states.value = chat(running = false)
        advanceTimeBy(600_000)

        assertEquals(NextPromptController.AFTER_TURN_MS.size, gateway.asked.size)
        assertTrue(NextPromptController.AFTER_TURN_MS.sum() <= 60_000)
    }

    @Test
    fun aFailedSuggestionStopsTheAsking() = runTest {
        val gateway = Gateway()
        val controller = controller(gateway)
        val states = MutableStateFlow(chat(running = true))
        controller.follow(url, null, states, MutableStateFlow(true))
        runCurrent()

        gateway.answers.apply { clear(); add("""{"suggestion":null,"error":{"kind":"auth","session_id":"s1","timestamp":1.0}}""") }
        states.value = chat(running = false)
        advanceTimeBy(120_000)

        assertNull(controller.suggestion.value)
        assertEquals(1, gateway.asked.size)
    }

    @Test
    fun theNextTurnTakesTheSuggestionAway() = runTest {
        val gateway = Gateway(answers = mutableListOf(suggestion("Yes, attach the chart")))
        val controller = controller(gateway)
        val states = MutableStateFlow(chat())
        controller.follow(url, null, states, MutableStateFlow(true))
        runCurrent()
        assertEquals("Yes, attach the chart", controller.suggestion.value?.text)

        states.value = chat(running = true)
        runCurrent()

        assertNull(controller.suggestion.value)
    }

    @Test
    fun anotherChatTakesTheSuggestionAway() = runTest {
        val gateway = Gateway(answers = mutableListOf(suggestion("Yes, attach the chart"), NONE))
        val controller = controller(gateway)
        controller.follow(url, null, MutableStateFlow(chat()), MutableStateFlow(true))
        runCurrent()

        controller.follow(url, null, MutableStateFlow(chat(id = "s2")), MutableStateFlow(true))
        runCurrent()

        assertNull(controller.suggestion.value)
        assertEquals(listOf("s1", "s2"), gateway.asked)
    }

    @Test
    fun dismissingTellsTheGateway() = runTest {
        val gateway = Gateway(answers = mutableListOf(suggestion("Restarted it, and it works now")))
        val controller = controller(gateway)
        controller.follow(url, "work", MutableStateFlow(chat()), MutableStateFlow(true))
        runCurrent()

        controller.dismiss()
        runCurrent()

        assertNull(controller.suggestion.value)
        assertEquals(listOf("s1"), gateway.dismissed)
    }

    @Test
    fun withoutThePluginNothingIsAsked() = runTest {
        val gateway = Gateway(installed = false)
        val controller = controller(gateway)
        val states = MutableStateFlow(chat(running = true))
        controller.follow(url, null, states, MutableStateFlow(true))
        runCurrent()
        states.value = chat(running = false)
        advanceTimeBy(120_000)

        assertNull(controller.suggestion.value)
        assertTrue(gateway.asked.isEmpty())
    }

    @Test
    fun aPluginInstalledLaterIsFoundOnceTheAnswerIsOld() = runTest {
        val gateway = Gateway(installed = false, answers = mutableListOf(suggestion("Run the tests")))
        var now = 0L
        val controller = controller(gateway) { now }
        controller.follow(url, null, MutableStateFlow(chat()), MutableStateFlow(true))
        runCurrent()
        assertEquals(1, gateway.listed)

        gateway.installed = true
        controller.follow(url, null, MutableStateFlow(chat()), MutableStateFlow(true))
        runCurrent()
        assertNull(controller.suggestion.value, "The answer from a moment ago still holds")
        assertEquals(1, gateway.listed)

        now += NextPromptController.MISSING_FRESH_MS
        controller.follow(url, null, MutableStateFlow(chat()), MutableStateFlow(true))
        runCurrent()
        assertEquals("Run the tests", controller.suggestion.value?.text)
    }

    @Test
    fun aPluginListedButNotServedStopsTheAsking() = runTest {
        val gateway = Gateway(answers = mutableListOf(NOT_SERVED))
        val controller = controller(gateway)
        val states = MutableStateFlow(chat(running = true))
        controller.follow(url, null, states, MutableStateFlow(true))
        runCurrent()

        states.value = chat(running = false)
        advanceTimeBy(10_000)
        assertEquals(1, gateway.asked.size)

        // Until the answer is old, the next turn doesn't ask either.
        states.value = chat(running = true)
        runCurrent()
        states.value = chat(running = false)
        advanceTimeBy(10_000)
        assertEquals(1, gateway.asked.size)

        // Restarted meanwhile: a turn after that finds it.
        gateway.answers.apply { clear(); add(suggestion("Run the tests")) }
        advanceTimeBy(NextPromptController.MISSING_FRESH_MS)
        states.value = chat(running = true)
        runCurrent()
        states.value = chat(running = false)
        advanceTimeBy(2_000)
        assertEquals("Run the tests", controller.suggestion.value?.text)
    }

    @Test
    fun theHistoryReloadingAsATurnEndsKeepsTheAsking() = runTest {
        val gateway = Gateway()
        val controller = controller(gateway)
        val states = MutableStateFlow(chat(running = true))
        controller.follow(url, null, states, MutableStateFlow(true))
        runCurrent()

        gateway.answers.apply { clear(); addAll(listOf(NONE, NONE, suggestion("Deploy to staging first"))) }
        states.value = chat(running = false, loaded = false)
        advanceTimeBy(500)
        states.value = chat(running = false)
        advanceTimeBy(5_000)

        assertEquals(SuggestedReply("s1", "Deploy to staging first"), controller.suggestion.value)
        assertEquals(3, gateway.asked.size)
    }

    @Test
    fun theSettingBackOnMidwayPicksTheAskingUp() = runTest {
        val gateway = Gateway()
        val controller = controller(gateway)
        val enabled = MutableStateFlow(true)
        val states = MutableStateFlow(chat(running = true))
        controller.follow(url, null, states, enabled)
        runCurrent()

        states.value = chat(running = false)
        advanceTimeBy(1_500)
        enabled.value = false
        advanceTimeBy(1_000)
        assertEquals(1, gateway.asked.size, "Off: no asking")

        gateway.answers.apply { clear(); add(suggestion("Run the tests")) }
        enabled.value = true
        advanceTimeBy(1_000)
        assertEquals("Run the tests", controller.suggestion.value?.text)
    }

    @Test
    fun theSettingOffHidesItAndAsksNothing() = runTest {
        val gateway = Gateway(answers = mutableListOf(suggestion("Run the tests")))
        val controller = controller(gateway)
        val enabled = MutableStateFlow(true)
        controller.follow(url, null, MutableStateFlow(chat()), enabled)
        runCurrent()
        assertEquals("Run the tests", controller.suggestion.value?.text)

        enabled.value = false
        runCurrent()

        assertNull(controller.suggestion.value)
        assertEquals(1, gateway.asked.size)
    }

    @Test
    fun aNewChatWithoutAStoredSessionAsksNothing() = runTest {
        val gateway = Gateway()
        val controller = controller(gateway)
        controller.follow(url, null, MutableStateFlow(chat(id = null)), MutableStateFlow(true))
        advanceTimeBy(60_000)

        assertTrue(gateway.asked.isEmpty())
    }

    private companion object {
        const val NONE = """{"suggestion":null,"error":null}"""

        /** The gateway lists the plugin but answers its route with a 404. */
        const val NOT_SERVED = "not served"

        fun suggestion(text: String) = """{"suggestion":{"text":"$text","session_id":"s1","timestamp":1.0},"error":null}"""
    }
}

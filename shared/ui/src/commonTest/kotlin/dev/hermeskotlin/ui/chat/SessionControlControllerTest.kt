package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ControlAction
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The panel's busy and error bookkeeping across chat switches, on chats that never connect. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionControlControllerTest {

    private fun TestScope.chat(id: String): ChatSession {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine { respond("{}") }, cookies)
        val chats = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> error("not connecting in tests") }, chats)
        return ChatSession(SavedGateway("https://hermes.example.ts.net").gatewayUrl, id, "Chat", connection, SessionsApi(client), chats)
    }

    @Test
    fun aFinishedActionClearsBusyAndShowsItsError() = runTest(StandardTestDispatcher()) {
        val a = chat("a")
        val controller = SessionControlController(this)
        controller.follow(a)
        controller.run(a, ControlAction.GoalPause)
        assertEquals(ControlAction.GoalPause, controller.state.value.busy)
        advanceUntilIdle()
        assertNull(controller.state.value.busy)
        // Offline, so the action failed and the sheet hears why.
        assertNotNull(controller.state.value.error)
    }

    @Test
    fun aChatSwitchDropsTheOldChatsLateResult() = runTest(StandardTestDispatcher()) {
        val (a, b) = chat("a") to chat("b")
        val controller = SessionControlController(this)
        controller.follow(a)
        controller.run(a, ControlAction.GoalPause)
        assertEquals(ControlAction.GoalPause, controller.state.value.busy)
        // The switch resets the panel, and the old chat's answer landing later isn't shown.
        controller.follow(b)
        assertNull(controller.state.value.busy)
        assertNull(controller.state.value.error)
        advanceUntilIdle()
        assertNull(controller.state.value.busy)
        assertNull(controller.state.value.error)
    }

    @Test
    fun aSecondActionIsIgnoredWhileOneIsBusy() = runTest(StandardTestDispatcher()) {
        val a = chat("a")
        val controller = SessionControlController(this)
        controller.follow(a)
        controller.run(a, ControlAction.GoalPause)
        controller.run(a, ControlAction.GoalClear)
        assertEquals(ControlAction.GoalPause, controller.state.value.busy)
    }
}

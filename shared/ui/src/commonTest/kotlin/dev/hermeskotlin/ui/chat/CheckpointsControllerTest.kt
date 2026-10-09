package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.Checkpoint
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
import kotlin.test.assertTrue

/** The sheet's bookkeeping, on chats that never connect: what it keeps, drops and asks again. */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointsControllerTest {

    private val checkpoint = Checkpoint("9d752f94aa", "2026-10-09T09:17:03Z", "")

    private fun TestScope.chat(id: String): ChatSession {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine { respond("{}") }, cookies)
        val chats = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> error("not connecting in tests") }, chats)
        return ChatSession(SavedGateway("https://hermes.example.ts.net").gatewayUrl, id, "Chat", connection, SessionsApi(client), chats)
    }

    @Test
    fun afterAChatSwitchTheOldChatsCallsAreIgnored() = runTest(StandardTestDispatcher()) {
        val (a, b) = chat("a") to chat("b")
        val controller = CheckpointsController(this)
        controller.load(a)
        // A chat opened under the open sheet takes it over.
        controller.follow(b)
        controller.loadDiff(a, checkpoint)
        assertTrue(controller.state.value.diffs.isEmpty())
        controller.restore(a, checkpoint)
        assertNull(controller.state.value.restoring)

        controller.loadDiff(b, checkpoint)
        assertTrue(checkpoint.hash in controller.state.value.diffs)
    }

    @Test
    fun aClosedSheetDoesntFollowTheChat() = runTest(StandardTestDispatcher()) {
        val (a, b) = chat("a") to chat("b")
        val controller = CheckpointsController(this)
        controller.load(a)
        controller.close()
        controller.follow(b)
        controller.loadDiff(a, checkpoint)
        assertTrue(checkpoint.hash in controller.state.value.diffs)
    }

    @Test
    fun reopeningMidRestoreKeepsRestoreOffButAnotherChatDoesnt() = runTest(StandardTestDispatcher()) {
        val (a, b) = chat("a") to chat("b")
        val controller = CheckpointsController(this)
        controller.load(a)
        controller.restore(a, checkpoint)
        controller.load(a)
        assertEquals(checkpoint.hash, controller.state.value.restoring)
        controller.load(b)
        assertNull(controller.state.value.restoring)
    }

    @Test
    fun aFailedDiffIsAskedAgainButALoadedOneIsnt() = runTest(StandardTestDispatcher()) {
        val a = chat("a")
        val controller = CheckpointsController(this)
        controller.load(a)
        controller.loadDiff(a, checkpoint)
        advanceUntilIdle()
        // Offline, so the read fails; the next ask tries again.
        assertNotNull(controller.state.value.diffs[checkpoint.hash]?.error)
        controller.loadDiff(a, checkpoint)
        assertNull(controller.state.value.diffs[checkpoint.hash])
        assertTrue(checkpoint.hash in controller.state.value.diffs)
    }
}

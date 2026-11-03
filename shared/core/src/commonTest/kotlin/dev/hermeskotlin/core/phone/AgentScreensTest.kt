package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.chat.AgentScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentScreensTest {

    private fun screen(vararg surfaces: String) = AgentScreen(listOf("{}"), surfaces.toSet(), wait = true)

    @Test
    fun aTapAnswersTheCallWaitingOnItsSurface() = runTest {
        val screens = AgentScreens()
        val call = async { screens.await(screen("pick"), "chat-a") }
        runCurrent()
        assertEquals(listOf(AgentScreens.Live("chat-a", setOf("pick"))), screens.live.value)
        screens.shown("chat-a", setOf("pick"))
        assertFalse(screens.answer("chat-a", "other", JsonPrimitive("x")))
        assertTrue(screens.answer("chat-a", "pick", JsonPrimitive("tapped")))
        assertEquals(JsonPrimitive("tapped"), call.await())
        // Answered once: the screen takes no more taps.
        assertEquals(emptyList(), screens.live.value)
        assertFalse(screens.answer("chat-a", "pick", JsonPrimitive("again")))
    }

    @Test
    fun theSameSurfaceInAnotherChatIsAnotherScreen() = runTest {
        val screens = AgentScreens()
        val a = async { screens.await(screen("pick-1"), "chat-a") }
        val b = async { screens.await(screen("pick-1"), "chat-b") }
        runCurrent()
        screens.shown("chat-a", setOf("pick-1"))
        screens.shown("chat-b", setOf("pick-1"))
        assertTrue(screens.answer("chat-b", "pick-1", JsonPrimitive("b")))
        assertEquals(JsonPrimitive("b"), b.await())
        assertTrue(a.isActive)
        screens.answer("chat-a", "pick-1", JsonPrimitive("a"))
        assertEquals(JsonPrimitive("a"), a.await())
    }

    @Test
    fun aScreenNoChatShowsSoonIsNotWaitedOn() = runTest {
        supervisorScope {
            val screens = AgentScreens(shownWithinMs = 1_000)
            val call = async { screens.await(screen("pick"), "chat-a") }
            runCurrent()
            // Shown in another chat: still not this one.
            screens.shown("chat-b", setOf("pick"))
            advanceTimeBy(1_001)
            assertEquals(AgentScreens.NOT_SHOWN, assertFailsWith<PhoneToolException> { call.await() }.message)
            assertEquals(emptyList(), screens.live.value)
        }
    }

    @Test
    fun aFailureReachesTheAgent() = runTest {
        supervisorScope {
            val screens = AgentScreens()
            val call = async { screens.await(screen("pick"), null) }
            runCurrent()
            // The gateway didn't say which chat: any chat showing the surface is it.
            screens.shown("chat-a", setOf("pick"))
            screens.fail("chat-a", "pick", "Unknown component Slidr")
            assertEquals("Unknown component Slidr", assertFailsWith<PhoneToolException> { call.await() }.message)
        }
    }

    @Test
    fun aNewerCallOnTheSameSurfaceTakesOver() = runTest {
        supervisorScope {
            val screens = AgentScreens()
            val first = async { screens.await(screen("pick"), "chat-a") }
            runCurrent()
            val second = async { screens.await(screen("pick", "more"), "chat-a") }
            runCurrent()
            assertEquals(AgentScreens.REPLACED, assertFailsWith<PhoneToolException> { first.await() }.message)
            assertEquals(listOf(AgentScreens.Live("chat-a", setOf("pick", "more"))), screens.live.value)
            screens.shown("chat-a", setOf("more"))
            screens.answer("chat-a", "more", JsonPrimitive("ok"))
            assertEquals(JsonPrimitive("ok"), second.await())
        }
    }

    @Test
    fun aCancelledCallLeavesNothingLive() = runTest {
        val screens = AgentScreens()
        val call = async { screens.await(screen("pick"), "chat-a") }
        runCurrent()
        call.cancel()
        runCurrent()
        assertEquals(emptyList(), screens.live.value)
    }
}

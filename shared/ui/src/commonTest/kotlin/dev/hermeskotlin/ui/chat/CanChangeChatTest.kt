package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanChangeChatTest {

    private val stored = ChatState(
        storedSessionId = "stored-1",
        messages = listOf(ChatMessage.User("u1", "hi"), ChatMessage.Assistant("a1", "hello")),
    )

    @Test
    fun aStoredIdleConnectedChatCanChange() {
        assertTrue(stored.canChangeChat(connected = true))
    }

    @Test
    fun notWhileOfflineRunningOrUnstored() {
        assertFalse(stored.canChangeChat(connected = false))
        assertFalse(stored.copy(running = true).canChangeChat(connected = true))
        assertFalse(stored.copy(storedSessionId = null).canChangeChat(connected = true))
    }

    @Test
    fun notWhileACommandWaitsOnItsAnswer() {
        val undoing = stored.copy(messages = stored.messages + ChatMessage.Command("c1", "/undo"))
        assertFalse(undoing.canChangeChat(connected = true))

        val answered = stored.copy(messages = stored.messages + ChatMessage.Command("c1", "/undo", running = false))
        assertTrue(answered.canChangeChat(connected = true))
    }

    @Test
    fun aSideQuestionStillRunningDoesNotBlock() {
        val asking = stored.copy(messages = stored.messages + ChatMessage.Command("c1", "/btw", taskId = "t1"))
        assertTrue(asking.canChangeChat(connected = true))
    }
}

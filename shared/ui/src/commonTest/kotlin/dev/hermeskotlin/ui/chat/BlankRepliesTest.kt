package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.TurnOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class BlankRepliesTest {

    private val thought = ChatMessage.Assistant("a1", reasoning = "The user is done.")
    private val messages = listOf(
        ChatMessage.User("u1", "Thanks, that is all."),
        thought,
        ChatMessage.Assistant("a2", text = "Done.", reasoning = "Short."),
        ChatMessage.Assistant("a3", reasoning = "Ran it.", tools = listOf(ToolActivity(id = "t1", name = "terminal"))),
        ChatMessage.Assistant("a4", reasoning = "Thinking.", streaming = true),
        ChatMessage.Assistant("a5", reasoning = "Stopped early.", outcome = TurnOutcome.Interrupted),
        ChatMessage.Assistant("a6", reasoning = "It failed.", outcome = TurnOutcome.Error, error = "Provider error"),
    )

    @Test
    fun aReplyOfOnlyReasoningIsNotListedWhileReasoningIsHidden() {
        assertEquals(messages - thought, messages.withoutBlankReplies(showReasoning = false))
    }

    @Test
    fun everyReplyIsListedWhileReasoningShows() {
        assertEquals(messages, messages.withoutBlankReplies(showReasoning = true))
    }
}

package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RewindTest {

    private fun prompt(n: Int, text: String = "p$n", rowId: Long? = n.toLong(), sentText: String? = text) =
        ChatMessage.User("u$n", text, rowId = rowId, sentText = sentText)

    private fun reply(n: Int) = ChatMessage.Assistant("a$n", text = "r$n")

    private val chat = ChatState(
        messages = listOf(
            prompt(1), reply(1),
            prompt(2), reply(2), ChatMessage.Notice("n", "a notice"),
            prompt(3), reply(3),
        ),
    )

    @Test
    fun aReplyRegeneratesFromThePromptItAnswered() {
        assertEquals("u2", chat.regenerateTarget("a2")?.key)
        assertNull(chat.regenerateTarget("u2"), "a prompt is not a reply")
        assertNull(chat.regenerateTarget("missing"))
    }

    @Test
    fun aPromptWithoutARowOrWhatWentOutCantRewind() {
        val noRow = chat.copy(messages = listOf(prompt(1, rowId = null), reply(1)))
        assertNull(noRow.regenerateTarget("a1"))
        assertFalse(noRow.canEdit("u1"))
        val pictures = chat.copy(messages = listOf(prompt(1, sentText = null), reply(1)))
        assertNull(pictures.regenerateTarget("a1"))
        assertFalse(pictures.canEdit("u1"))
        val pending = chat.copy(messages = listOf(prompt(1).copy(pending = true)))
        assertFalse(pending.canEdit("u1"))
    }

    @Test
    fun aSkillRegeneratesButIsntEditedAsItsCommand() {
        val skill = chat.copy(messages = listOf(prompt(1, text = "/work fix it", sentText = "[skill body] fix it"), reply(1)))
        assertEquals("u1", skill.regenerateTarget("a1")?.key)
        assertFalse(skill.canEdit("u1"))
        assertTrue(chat.canEdit("u1"))
    }

    @Test
    fun aPromptBeingEditedIsFollowedToItsStoredKey() {
        // Sent from here as local-4 with row 4; a reload shows the same row under its stored key.
        val reloaded = chat.copy(messages = chat.messages + ChatMessage.User("row-4", "p4", rowId = 4))
        assertEquals("u2", reloaded.promptNow("u2", 2))
        assertEquals("row-4", reloaded.promptNow("local-4", 4))
        assertNull(reloaded.promptNow("local-4", null))
        assertNull(chat.promptNow("u9", 9), "cut away")
    }

    @Test
    fun countsWhatComesAfterTheExchange() {
        // Prompt 1's exchange is replaced; prompts 2 and 3 with their replies go. Notices aren't messages.
        assertEquals(4, chat.discardedAfter("u1"))
        assertEquals(2, chat.discardedAfter("u2"))
        assertEquals(0, chat.discardedAfter("u3"))
    }
}

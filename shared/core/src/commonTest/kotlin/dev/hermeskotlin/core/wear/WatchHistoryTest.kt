package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchHistoryTest {

    private fun row(id: Long, role: String, text: String) = SessionMessage(id = id, role = role, content = JsonPrimitive(text))

    @Test
    fun aChatOpensOnItsLastPromptAndTheReplyToIt() {
        val rows = listOf(
            row(1, "user", "First question"),
            row(2, "assistant", "First answer"),
            row(3, "user", "Second question"),
            row(4, "assistant", "Second answer"),
        )
        assertEquals(LastExchange("Second question", "Second answer"), lastExchange(rows))
    }

    @Test
    fun aPromptStillWaitingShowsTheReplyBeforeIt() {
        val rows = listOf(row(1, "user", "Asked"), row(2, "assistant", "Answered"), row(3, "user", "Not answered yet"))
        assertEquals(LastExchange("Asked", "Answered"), lastExchange(rows))
    }

    @Test
    fun aChatWithoutRepliesHasNothingToShow() {
        assertNull(lastExchange(emptyList()))
        assertNull(lastExchange(listOf(row(1, "user", "Hello?"))))
    }
}

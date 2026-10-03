package dev.hermeskotlin.ui.transcript

import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptItemsTest {

    private fun toolCall(name: String) = buildJsonArray {
        add(buildJsonObject { put("id", "c-$name"); put("function", buildJsonObject { put("name", name) }) })
    }

    @Test
    fun toolStepsMergeIntoOneReplyAndToolRowsDropOut() {
        val items = toItems(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("list files")),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(""), toolCalls = toolCall("terminal")),
                SessionMessage(id = 3, role = "tool", content = JsonPrimitive("a.txt"), toolCallId = "c-terminal"),
                SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("There is one file."), toolCalls = null),
                SessionMessage(id = 5, role = "user", content = JsonPrimitive("summary"), displayKind = "hidden"),
                SessionMessage(id = 6, role = "system", content = JsonPrimitive("You are Hermes")),
            ),
        )

        assertEquals(
            listOf(
                TranscriptItem.User("1", "list files"),
                TranscriptItem.Assistant("2", "There is one file.", listOf("terminal")),
            ),
            items,
        )
    }

    @Test
    fun aNewUserPromptStartsANewReply() {
        val items = toItems(
            listOf(
                SessionMessage(id = 1, role = "assistant", content = JsonPrimitive("Hi")),
                SessionMessage(id = 2, role = "user", content = JsonPrimitive("Hello")),
                SessionMessage(id = 3, role = "assistant", content = JsonPrimitive("How can I help?")),
            ),
        )
        assertEquals(3, items.size)
    }
}

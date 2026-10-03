package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryToMessagesTest {

    private fun toolCall(name: String) = buildJsonArray {
        add(buildJsonObject { put("id", "c-$name"); put("function", buildJsonObject { put("name", name) }) })
    }

    @Test
    fun toolStepsMergeIntoOneReplyAndToolRowsDropOut() {
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("list files")),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(""), toolCalls = toolCall("terminal")),
                SessionMessage(id = 3, role = "tool", content = JsonPrimitive("a.txt"), toolCallId = "c-terminal"),
                SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("There is one file.")),
                SessionMessage(id = 5, role = "user", content = JsonPrimitive("summary"), displayKind = "hidden"),
                SessionMessage(id = 6, role = "system", content = JsonPrimitive("You are Hermes")),
            ),
        )

        assertEquals(
            listOf(
                ChatMessage.User("row-1", "list files"),
                ChatMessage.Assistant("row-2", text = "There is one file.", tools = listOf(ToolActivity("row-2-0", "terminal"))),
            ),
            messages,
        )
    }

    @Test
    fun aNewUserPromptStartsANewReply() {
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "assistant", content = JsonPrimitive("Hi")),
                SessionMessage(id = 2, role = "user", content = JsonPrimitive("Hello")),
                SessionMessage(id = 3, role = "assistant", content = JsonPrimitive("How can I help?")),
            ),
        )
        assertEquals(3, messages.size)
    }

    @Test
    fun storedPromptsShowTheirImagesAndFileReferencesAsAttachments() {
        val parts = Json.parseToJsonElement(
            """[{"type":"text","text":"What is this?"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AA"}}]""",
        )
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = parts),
                SessionMessage(id = 2, role = "user", content = JsonPrimitive("@file:attachments/notes.txt\n@file:\"attachments/my spec.pdf\"\n\nsummarise both")),
                SessionMessage(id = 3, role = "user", content = JsonPrimitive("Describe this\n@image:/opt/data/images/upload_1.jpg")),
            ),
        )
        val stored = messages[2] as ChatMessage.User
        assertEquals("Describe this", stored.text)
        assertEquals("/opt/data/images/upload_1.jpg", stored.attachments.single().gatewayPath)
        assertEquals("upload_1.jpg", stored.attachments.single().name)
        val image = messages[0] as ChatMessage.User
        assertEquals("What is this?", image.text)
        assertEquals(listOf(AttachmentKind.Image), image.attachments.map { it.kind })
        val files = messages[1] as ChatMessage.User
        assertEquals("summarise both", files.text)
        assertEquals(listOf("notes.txt", "my spec.pdf"), files.attachments.map { it.name })
        assertEquals(listOf(AttachmentKind.File, AttachmentKind.Pdf), files.attachments.map { it.kind })
    }
}

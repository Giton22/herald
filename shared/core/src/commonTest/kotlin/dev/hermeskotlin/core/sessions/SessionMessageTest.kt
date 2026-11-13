package dev.hermeskotlin.core.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionMessageTest {
    @Test
    fun structuredTextPartsDoNotPreventReadingTheRestOfTheMessage() {
        val parts = Json.parseToJsonElement(
            """[{"type":"text","text":"Before"},{"text":{"value":"unsupported"}},{"text":["unsupported"]},{"text":null},{"type":"image_url","image_url":{"url":"example"}},{"type":"text","text":"After"}]""",
        )
        assertEquals("Before\nAfter", SessionMessage(role = "user", content = parts).text)
        assertEquals(
            "Before\nAfter",
            SessionMessage(role = "assistant", content = JsonPrimitive("Raw"), displayContent = parts).text,
        )
    }

    @Test
    fun anArrayWithOnlyUnsupportedTextPartsHasNoPlainText() {
        val parts = Json.parseToJsonElement("""[{"text":{}},{"text":[]},{"text":null},{}]""")
        assertEquals("", SessionMessage(role = "user", content = parts).text)
    }

    @Test
    fun ordinaryMultimodalTextStillReadsInOrder() {
        val parts = Json.parseToJsonElement(
            """["First",{"type":"text","text":"Second"},{"type":"image_url","image_url":{"url":"example"}}]""",
        )
        assertEquals("First\nSecond", SessionMessage(role = "user", content = parts).text)
    }
}

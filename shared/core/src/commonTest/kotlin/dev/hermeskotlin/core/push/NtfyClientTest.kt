package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NtfyClientTest {

    private val topic = "hp-0123456789abcdef0123456789abcdef"

    private fun client(body: String, seen: MutableList<String> = mutableListOf()) = NtfyClient(
        createHttpClient(MockEngine { request -> seen += request.url.toString(); respond(body) }),
    )

    @Test
    fun readsOnlyMessagesOfItsOwnTopic() = runTest {
        val urls = mutableListOf<String>()
        val body = listOf(
            """{"id":"o1","event":"open","topic":"$topic"}""",
            """{"id":"m1","event":"message","topic":"$topic","message":"one"}""",
            """{"id":"m2","event":"message","topic":"hp-ffffffffffffffffffffffffffffffff","message":"other"}""",
            "not json",
            """{"id":"m3","event":"keepalive","topic":"$topic"}""",
        ).joinToString("\n")
        val events = client(body, urls).subscribe("https://ntfy.example", topic, since = "abc").toList()
        assertEquals(listOf("one"), events.map { it.message })
        assertTrue(urls.single().endsWith("/$topic/json?since=abc"))
    }

    @Test
    fun anEndlessLineIsRefusedNotBuffered() = runTest {
        val huge = "x".repeat(200_000)
        assertFailsWith<Exception> { client(huge).subscribe("https://ntfy.example", topic, null).toList() }
    }

    @Test
    fun onlyPushTopicsAreAddressed() = runTest {
        assertFailsWith<IllegalArgumentException> { client("").subscribe("https://ntfy.example", "../admin", null).toList() }
    }
}

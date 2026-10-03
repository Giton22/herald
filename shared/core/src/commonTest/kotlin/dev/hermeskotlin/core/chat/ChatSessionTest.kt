package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatSessionTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val history = """{"session_id":"stored-1","messages":[
        {"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"}]}"""

    private fun client() = createHttpClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                "/api/sessions/stored-1/messages" -> respond(history, HttpStatusCode.OK, json)
                else -> error("unexpected ${request.url}")
            }
        },
    )

    /** Answers every client request with the canned result for its method (`{}` otherwise). */
    private fun CoroutineScope.serve(transport: FakeTransport, results: Map<String, String>) = launch {
        var answered = 0
        transport.sent.collect { sent ->
            sent.drop(answered).forEach { message ->
                answered++
                val id = message["id"] ?: return@forEach
                val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                transport.push("""{"jsonrpc":"2.0","id":$id,"result":${results[method] ?: "{}"}}""")
            }
        }
    }

    private fun JsonObject.isCall(method: String) = this["method"]?.jsonPrimitive?.contentOrNull == method

    private fun JsonObject.param(name: String) = this["params"]?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull

    private fun event(type: String, sessionId: String, payload: String = "{}") =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$sessionId","payload":$payload}}"""

    private fun setup(scope: CoroutineScope, results: Map<String, String>): Pair<GatewayConnection, FakeTransport> {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val http = client()
        val transport = FakeTransport()
        val connection = GatewayConnection(AuthApi(http, cookies), { _, _ -> transport }, scope)
        scope.serve(transport, results)
        connection.start(url)
        transport.push(FakeTransport.READY)
        return connection to transport
    }

    @Test
    fun resumesStoredSessionAndStreamsItsTurn() = runTest {
        val (connection, transport) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false,"info":{"model":"m1"}}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()

        val resume = transport.awaitSent { it.isCall("session.resume") }
        assertEquals("stored-1", resume.param("session_id"))
        assertEquals("true", resume.param("omit_messages"))
        val attached = chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        assertEquals(2, attached.messages.size)
        assertEquals("m1", attached.model)

        transport.push(event("message.delta", "someone-else", """{"text":"not ours"}"""))
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.delta", "rt1", """{"text":"Streaming"}"""))
        transport.push(event("message.complete", "rt1", """{"text":"Streaming","status":"complete"}"""))

        val done = chat.state.first { s -> s.messages.size == 3 && !s.running }
        assertEquals("Streaming", (done.messages.last() as ChatMessage.Assistant).text)
    }

    @Test
    fun firstSendOnANewChatCreatesTheSessionThenSubmits() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}""",
                "prompt.submit" to """{"status":"streaming"}""",
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        chat.start()

        assertTrue(chat.send("  write a haiku "))

        val submit = transport.awaitSent { it.isCall("prompt.submit") }
        assertEquals("rt9", submit.param("session_id"))
        assertEquals("write a haiku", submit.param("text"))
        val state = chat.state.value
        assertEquals("stored-9", state.storedSessionId)
        assertTrue(state.running)
        val user = assertIs<ChatMessage.User>(state.messages.single())
        assertFalse(user.pending)
    }

    @Test
    fun sendWhileOfflineReportsAndLeavesNoBubble() = runTest {
        val connection = GatewayConnection(
            AuthApi(client(), PersistentCookiesStorage(InMemoryKeyValueStore())),
            { _, _ -> error("offline") },
            backgroundScope,
        )
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)

        assertFalse(chat.send("hello?"))

        assertTrue(chat.state.value.messages.isEmpty())
        assertTrue(chat.state.value.error != null)
    }
}

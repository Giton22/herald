package dev.hermeskotlin.core.rpc

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.ContinuationInterceptor

/**
 * A gateway for tests: a [connection] whose sockets are [FakeTransport]s that answer each call through [answer],
 * and a [client] that hands out ws-tickets and answers every other request through [http].
 *
 * Every connect opens a fresh socket ([sockets], newest last) that greets with [ready], so a dropped link comes
 * back. With `reconnects = false` every connect gets the same socket, so once it closes the link stays down.
 */
class FakeGateway(private val scope: CoroutineScope, private val reconnects: Boolean = true) {

    /** A call the client sent on [socket]. */
    class Call(val id: JsonElement, val method: String, val params: JsonObject, val socket: FakeTransport) {
        fun param(name: String) = params[name]?.jsonPrimitive?.contentOrNull

        /** The frame that answers this call with [result]. */
        fun reply(result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

        /** The frame that turns this call down; [reason] goes in `data.reason`. */
        fun error(code: Int, message: String = "nope", reason: String? = null) =
            """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"${reason?.let { ""","data":{"reason":"$it"}""" }.orEmpty()}}}"""
    }

    /**
     * How a call is answered: a JSON result, a whole frame (from [Call.error] or [Call.reply]), `error:<code>` or
     * `error:<code>:<reason>` for a JSON-RPC error, or null to stay silent.
     */
    var answer: (Call) -> String? = { "{}" }

    /** Answers each request but the ws-ticket; fails the test unless set. */
    var http: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("unexpected ${it.url}") }

    /** What each new socket greets with. */
    var ready: () -> String = { FakeTransport.READY }

    /** Every socket the connection opened, newest last. */
    val sockets = mutableListOf<FakeTransport>()

    val socket get() = sockets.last()

    /** Every call the client sent, on any socket. */
    val sent get() = sockets.flatMap { it.sent.value }

    fun sent(method: String) = sent.filter { it.isCall(method) }

    /**
     * On the test's own dispatcher: an answer coming on a real thread lets the test clock jump ahead while it
     * waits, so a request's timeout or the heartbeat could lapse in no time and the test flake.
     */
    val client: HttpClient = createHttpClient(
        MockEngine(
            MockEngineConfig().apply {
                (scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher)?.let { dispatcher = it }
                addHandler { request ->
                    if (request.url.encodedPath == "/api/auth/ws-ticket") json("""{"ticket":"T","ttl_seconds":30}""")
                    else http(request)
                }
            },
        ),
    )

    val connection = GatewayConnection(
        AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())),
        { _, _ -> if (reconnects || sockets.isEmpty()) open() else socket },
        scope,
    )

    /** Starts the [connection] and waits until it is connected. */
    suspend fun start(): GatewayConnection {
        connection.start(URL)
        connection.state.first { it is ConnectionState.Connected }
        return connection
    }

    private fun open() = FakeTransport().also { transport ->
        sockets += transport
        scope.launch {
            var answered = 0
            transport.sent.collect { sent ->
                sent.drop(answered).forEach { message ->
                    answered++
                    val id = message["id"] ?: return@forEach
                    val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val call = Call(id, method, message["params"]?.jsonObject ?: JsonObject(emptyMap()), transport)
                    val canned = answer(call) ?: return@forEach
                    val frame = when {
                        canned.startsWith("""{"jsonrpc"""") -> canned
                        canned.startsWith("error:") -> canned.removePrefix("error:").split(':', limit = 2)
                            .let { call.error(it[0].toInt(), reason = it.getOrNull(1)) }
                        else -> call.reply(canned)
                    }
                    // One line per frame, the way the gateway sends them: the client reads frames line by line.
                    transport.push(frame.replace('\n', ' ').replace('\r', ' '))
                }
            }
        }
        transport.push(ready())
    }

    companion object {
        val URL = GatewayUrl.parse("https://hermes.example.ts.net")

        /** `gateway.ready` without the heartbeat, so moving the test clock never trips it. */
        const val QUIET_READY = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
    }
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

/** A gateway event frame; [sessionId] and [seq] are left out when null. */
fun event(type: String, sessionId: String? = null, payload: String = "{}", seq: Long? = null) =
    """{"jsonrpc":"2.0","method":"event","params":{"type":"$type",""" +
        sessionId?.let { """"session_id":"$it",""" }.orEmpty() + seq?.let { """"seq":$it,""" }.orEmpty() + """"payload":$payload}}"""

fun JsonObject.isCall(method: String) = this["method"]?.jsonPrimitive?.contentOrNull == method

fun JsonObject.param(name: String) = this["params"]?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull

package dev.hermeskotlin.core.rpc

import dev.hermeskotlin.core.network.HermesJson
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A server `event` notification: `{"method":"event","params":{"type":…,"payload":…}}`. */
data class GatewayEvent(
    val type: String,
    val payload: JsonElement?,
    val sessionId: String?,
    val seq: Long?,
)

/**
 * JSON-RPC error returned by the gateway. [reason] is the machine-readable cause some errors carry in
 * `data.reason` (`SESSION_NOT_OWNED` on a 4090, a delivery failure class), so callers needn't read prose.
 */
class RpcException(val code: Int, override val message: String, val reason: String? = null) : Exception(message)

/**
 * No reply to [method] within its timeout. The call went out, so the gateway may still have acted on it.
 * An ordinary exception, not a cancellation: callers that rethrow cancellations would skip their cleanup.
 */
class RpcTimeoutException(val method: String) : Exception("The gateway didn't answer $method in time.")

/** The gateway stopped answering (no inbound frame within the heartbeat deadline). */
class HeartbeatTimeoutException : Exception("Gateway heartbeat timed out")

/**
 * A server→client request (approval, clarify, sudo, secret…), answered with [JsonRpcClient.respond].
 * The gateway sends it to every client attached to [sessionId] and the first answer wins, so a
 * client answers only what it can show and declines the rest (see `JsonRpcClient.answers`).
 */
data class ServerRequest(
    val id: String,
    val method: String,
    val params: JsonObject,
) {
    val sessionId: String? get() = params["session_id"]?.jsonPrimitive?.contentOrNull
}

/**
 * A request this app sent: its [method], the [sessionId] it was for (prompts carry whole images, so no more), and
 * when, [atMillis] by the client's clock.
 */
data class SentRequest(val method: String, val sessionId: String?, val atMillis: Long)

/**
 * JSON-RPC 2.0 peer over the dashboard WebSocket (tui_gateway/ws.py + apps/shared/json-rpc-channel.ts).
 *
 * Call [run] to pump the socket; it returns only by throwing (closed, heartbeat timeout).
 * On `gateway.ready` it advertises `client.capabilities {server_requests: true}` and, when the server
 * offers it, starts the `gateway.ping` heartbeat (15 s interval, 45 s inbound deadline).
 */
class JsonRpcClient(
    private val transport: RpcTransport,
    private val heartbeatIntervalMs: Long = 15_000,
    private val heartbeatDeadlineMs: Long = 45_000,
    private val clock: () -> Long = { getTimeMillis() },
    /**
     * The server→client request methods this app can show. Any other is declined ([SESSION_NOT_SHOWN]),
     * which leaves it to a Desktop window and, once every client declined, ends the agent's wait at once.
     */
    private val answers: (String) -> Boolean = { true },
) {
    private val sendMutex = Mutex()

    /** Held by the one request over [LARGE_FRAME_CHARS] that's out, from sending it to its reply. */
    private val largeFrames = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private val pendingMutex = Mutex()
    /** Taken only under [pendingMutex]: callers run on several threads, and a shared id hands one caller another's reply. */
    private var nextId = 0L

    /** The heartbeat's own count, so a ping never races a request for an id. */
    private var nextPing = 0L
    @kotlin.concurrent.Volatile private var lastInboundAt = clock()
    private var heartbeatJob: Job? = null

    /** The gateway counts a [SESSION_NOT_SHOWN] decline instead of taking it as the answer (`client.capabilities`). */
    @kotlin.concurrent.Volatile private var declinesNotShown = false

    /** Why [run] ended; set once the link is gone for good. */
    @kotlin.concurrent.Volatile private var closedWith: Throwable? = null

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _serverRequests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 16)

    /**
     * Requests are never failed on the user's behalf: an error reply settles the request for every
     * client (an approval is withdrawn even while Desktop shows it). One nobody answers here stays
     * open until another client answers it, it times out, or `session.resume` replays it.
     */
    val serverRequests: SharedFlow<ServerRequest> = _serverRequests.asSharedFlow()

    private val _sent = MutableSharedFlow<SentRequest>(replay = SENT_REPLAY, extraBufferCapacity = 64)

    /**
     * Each request as it goes out, for what has to follow what the user sends (which phone a chat is on). The
     * last few are replayed, so one sent the moment the link came up isn't missed by a collector starting then.
     */
    val sent: SharedFlow<SentRequest> = _sent.asSharedFlow()

    /** Now, by the clock [sent] stamps requests with. */
    fun clockMillis(): Long = clock()

    /** Completed with the `gateway.ready` payload. */
    val ready = CompletableDeferred<JsonObject>()

    /**
     * The gateway runs this socket's chats on its session authority (`gateway.ready` `session_authority`), which
     * takes requests in its own shape; [request] rewrites them ([AuthorityRequests]). Known once [ready] is.
     */
    @kotlin.concurrent.Volatile var sessionAuthority = false
        private set

    private val authorityRequests = AuthorityRequests()

    suspend fun run(): Nothing = coroutineScope {
        try {
            transport.incoming.collect { text ->
                lastInboundAt = clock()
                // One JSON document per frame today; split defensively in case frames get batched.
                text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { handleLine(it, this) }
            }
            throw TransportClosedException(null, "stream ended")
        } catch (e: Throwable) {
            // The heartbeat giving up cancels this scope. Callers must see a lost link, not a cancellation:
            // theirs would end silently and leave whatever waited on the reply spinning.
            val failure = if (e is CancellationException) {
                e.cause?.takeUnless { it is CancellationException } ?: TransportClosedException(null, "connection lost")
            } else {
                e
            }
            closedWith = failure
            failPending(failure)
            if (!ready.isCompleted) ready.completeExceptionally(failure)
            throw e
        } finally {
            heartbeatJob?.cancel()
        }
    }

    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 30_000): JsonElement {
        if (!sessionAuthority) return call(method, params, timeoutMs)
        val sent = authorityRequests.prepare(method, params)
        return call(method, sent, timeoutMs).also { authorityRequests.answered(method, sent, it) }
    }

    private suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonElement {
        // Nothing will answer on a link that is gone; say so now rather than after the timeout.
        closedWith?.let { throw it }
        val deferred = CompletableDeferred<JsonElement>()
        val id = pendingMutex.withLock { "c${++nextId}".also { pending[it] = deferred } }
        try {
            val frame = encode(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
            // OkHttp's 16 MiB is for everything still queued, not each message: one upload draining over a slow
            // link and a second one queued behind it would close the socket. So one large message is out at a
            // time, until its reply shows the gateway has all of it.
            val large = frame.length > LARGE_FRAME_CHARS
            if (large) largeFrames.lock()
            try {
                // A cast, not jsonPrimitive: a request whose session_id isn't a string must still go out.
                _sent.tryEmit(SentRequest(method, (params["session_id"] as? JsonPrimitive)?.contentOrNull, clock()))
                sendText(frame)
                // withTimeout would throw a cancellation, indistinguishable from the caller being cancelled.
                return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: throw RpcTimeoutException(method)
            } finally {
                if (large) largeFrames.unlock()
            }
        } finally {
            pendingMutex.withLock { pending.remove(id) }
        }
    }

    /**
     * Answers server request [id], live or replayed from `open_requests`. A late answer is harmless:
     * the gateway drops responses for requests that are no longer open.
     */
    suspend fun respond(id: String, result: JsonElement) = send(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    })

    suspend fun close() = transport.close()

    private suspend fun handleLine(line: String, scope: CoroutineScope) {
        val frame = runCatching { HermesJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val id = frame["id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val method = frame["method"]?.jsonPrimitive?.contentOrNull

        when {
            method == "event" -> handleEvent(frame["params"] as? JsonObject ?: return, scope)
            method != null && id != null -> handleServerRequest(id, method, frame["params"])
            id != null -> {
                val deferred = pendingMutex.withLock { pending.remove(id) } ?: return
                val error = frame["error"] as? JsonObject
                if (error != null) {
                    deferred.completeExceptionally(
                        RpcException(
                            error["code"]?.jsonPrimitive?.intOrNull ?: -32000,
                            error["message"]?.jsonPrimitive?.contentOrNull ?: "RPC error",
                            ((error["data"] as? JsonObject)?.get("reason") as? JsonPrimitive)
                                ?.takeIf { it.isString }?.contentOrNull,
                        ),
                    )
                } else {
                    deferred.complete(frame["result"] ?: JsonNull)
                }
            }
        }
    }

    private suspend fun handleEvent(params: JsonObject, scope: CoroutineScope) {
        val type = params["type"]?.jsonPrimitive?.contentOrNull ?: return
        val payload = params["payload"]
        if (type == "gateway.ready") {
            val readyPayload = payload as? JsonObject ?: JsonObject(emptyMap())
            scope.launch {
                runCatching {
                    val reply = request("client.capabilities", buildJsonObject { put("server_requests", true) }) as? JsonObject
                    declinesNotShown = reply?.get("declines_not_shown")?.jsonPrimitive?.booleanOrNull == true
                }
            }
            if (readyPayload["heartbeat"]?.jsonPrimitive?.booleanOrNull == true) startHeartbeat(scope)
            sessionAuthority = (readyPayload["session_authority"] as? JsonPrimitive)?.booleanOrNull == true
            ready.complete(readyPayload)
        }
        _events.emit(
            GatewayEvent(
                type = type,
                payload = payload,
                sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull,
                seq = params["seq"]?.jsonPrimitive?.longOrNull,
            ),
        )
    }

    private suspend fun handleServerRequest(id: String, method: String, params: JsonElement?) {
        // Any error settles a request for every client, and Desktop may show what this app can't (its window
        // bridges, guided tours), so the rest gets the decline the gateway only counts (settling once every
        // client declined). A gateway that doesn't count declines would take it as the answer and cut Desktop
        // off: say nothing there.
        if (answers(method)) {
            _serverRequests.emit(ServerRequest(id, method, params as? JsonObject ?: JsonObject(emptyMap())))
        } else if (declinesNotShown) {
            respondError(id, SESSION_NOT_SHOWN, "This app can't show $method.")
        }
    }

    private suspend fun respondError(id: String, code: Int, message: String) = runCatching {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        })
    }

    private fun startHeartbeat(scope: CoroutineScope) {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                if (clock() - lastInboundAt > heartbeatDeadlineMs) {
                    runCatching { transport.close(GOING_AWAY, "heartbeat timeout") }
                    throw HeartbeatTimeoutException()
                }
                // Fire-and-forget: any inbound frame (including the pong) counts as liveness.
                runCatching {
                    send(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", "ping${++nextPing}")
                        put("method", "gateway.ping")
                        put("params", JsonObject(emptyMap()))
                    })
                }
            }
        }
    }

    private suspend fun send(frame: JsonObject) = sendText(encode(frame))

    private suspend fun sendText(text: String) = sendMutex.withLock { transport.send(text) }

    /**
     * [frame] as it goes on the wire, or [RpcException] ([MESSAGE_TOO_LARGE]) before anything is sent when
     * it's over [MAX_FRAME_BYTES]: OkHttp drops a message that would queue more and closes the whole socket.
     */
    private fun encode(frame: JsonObject): String {
        val text = HermesJson.encodeToString(JsonObject.serializer(), frame)
        if (utf8Size(text) > MAX_FRAME_BYTES) throw RpcException(MESSAGE_TOO_LARGE, "That's too large to send to the gateway.")
        return text
    }

    private suspend fun failPending(cause: Throwable) {
        val calls = pendingMutex.withLock { pending.values.toList().also { pending.clear() } }
        calls.forEach { it.completeExceptionally(cause) }
    }

    companion object {
        private const val GOING_AWAY: Short = 1001

        /** How many sent requests [sent] replays to a new collector. */
        private const val SENT_REPLAY = 16

        /** tui_gateway/server_requests.py `NOT_SHOWN_CODE`: nothing in this client shows the request. */
        const val SESSION_NOT_SHOWN = 4404

        /** This client's own code, never the gateway's: the request was too large and didn't go out. */
        const val MESSAGE_TOO_LARGE = -1

        /**
         * The largest message this client sends. OkHttp closes the socket (1001) rather than queue more than
         * 16 MiB, and the gateway's own limit is far above that. Only one message over [LARGE_FRAME_CHARS] is
         * out at a time, so the 512 KiB spare is for the pings and small requests queued beside it.
         */
        const val MAX_FRAME_BYTES = 16 * 1024 * 1024 - 512 * 1024

        /** A message longer than this waits for the large one before it to be answered (see [request]). */
        internal const val LARGE_FRAME_CHARS = 64 * 1024
    }
}

/** [text]'s length in UTF-8, without encoding it: a frame can hold megabytes of base64. */
internal fun utf8Size(text: String): Long {
    var size = 0L
    var i = 0
    while (i < text.length) {
        val c = text[i]
        size += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> 4.also { i++ }
            else -> 3
        }
        i++
    }
    return size
}

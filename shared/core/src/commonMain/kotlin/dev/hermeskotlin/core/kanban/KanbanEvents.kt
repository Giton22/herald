package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.HandshakeRejectedException
import dev.hermeskotlin.core.rpc.handshakeStatus
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json

/**
 * Live tail of one board's `task_events` over `/api/plugins/kanban/events?board=<slug>&since=<cursor>`.
 * The socket pins the board at the handshake (a board change opens a new socket, per the dashboard); every
 * newer event the gateway appends arrives as `{events, cursor}` and the view model debounces a reload.
 */
fun interface KanbanEvents {
    /** Frames for [board], starting at [since] (`null` starts at the tail, never at 0, so history isn't replayed). */
    fun observe(url: GatewayUrl, board: String, since: Long?): Flow<KanbanEventsFrame>
}

/** The connected socket, thinly abstracted so the retry loop is testable without a network. */
internal interface KanbanEventsSocket {
    val incoming: Flow<Frame>

    /** The close reason once the socket has ended; null when it dropped without a close frame. */
    suspend fun closeReason(): CloseReason?

    suspend fun close()
}

private class KtorKanbanEventsSocket(private val session: DefaultClientWebSocketSession) : KanbanEventsSocket {
    override val incoming: Flow<Frame> get() = session.incoming.receiveAsFlow()
    override suspend fun closeReason(): CloseReason? = session.closeReason.await()
    override suspend fun close() = session.close()
}

/**
 * [KanbanEvents] over a WebSocket. Browsers can't set `Authorization` on an upgrade or a subprotocol the
 * plugin gate understands (`?token=` / `?ticket=` / `?internal=`), so the socket carries a fresh single-use
 * `?ticket=` from `POST /api/auth/ws-ticket` in the query string — 30 s TTL, minted per (re)connect.
 *
 * Failures climb an exponential backoff ladder capped at [BACKOFF_MS]'s last step; a session that delivered
 * a frame proved itself and starts the ladder over. Some failures are terminal, not transient:
 * a 1008 close after the accept (auth refused, or a stale slug naming an archived/deleted board), a
 * redirect (3xx) or 404 handshake, and a refused upgrade (403 — uvicorn turns the plugin gate's
 * pre-accept refusal into plain HTTP 403, as on `/api/ws`) after [REFUSAL_LIMIT] fresh-ticket attempts.
 * Terminal ends complete the flow; only a new `observe` (board change, page reopen) connects again.
 */
class GatewayKanbanEvents internal constructor(
    private val client: HttpClient,
    private val auth: AuthApi,
    /** Tests substitute the socket opener; production opens [client]'s WebSocket. */
    private val openSocket: (suspend (String) -> KanbanEventsSocket)? = null,
) : KanbanEvents {

    private val open: suspend (String) -> KanbanEventsSocket
        get() = openSocket ?: { url -> KtorKanbanEventsSocket(client.webSocketSession(url)) }

    override fun observe(url: GatewayUrl, board: String, since: Long?): Flow<KanbanEventsFrame> = flow {
        var cursor = since
        var attempt = 0
        var refusals = 0
        val open = open
        while (true) {
            currentCoroutineContext().ensureActive()
            val minted = auth.mintWsTicket(url)
            val ticket = (minted as? ApiResult.Success)?.value?.ticket
            if (ticket == null) {
                when {
                    // Signed out (or an old gateway without the ticket route): minting again only repeats
                    // the refusal, and the REST surface already routes the user to sign in.
                    minted == ApiResult.SessionExpired || minted is ApiResult.Failed && minted.status == 404 ||
                        minted == ApiResult.InvalidCredentials -> return@flow
                    // Offline / rate limited / otherwise transient: keep waiting, quietly.
                    else -> delay(backoffMs(++attempt))
                }
                continue
            }
            var gotFrame = false
            // The HTTP status when the session threw instead of closing cleanly (OkHttp surfaces a refused
            // upgrade as "Expected HTTP 101 ... but was '403'", see `handshakeStatus`).
            var handshake: Int? = null
            val closedCode: Short? = try {
                val socket = open(eventsUrl(url, board, ticket, cursor))
                try {
                    socket.incoming.collect { frame ->
                        if (frame is Frame.Text) {
                            val parsed = parseEventsFrame(frame.readText())
                            if (parsed != null) {
                                gotFrame = true
                                cursor = parsed.cursor
                                if (parsed.events.isNotEmpty()) emit(parsed)
                            }
                        }
                    }
                } finally {
                    // Runs on collector cancellation too: the socket never outlives its flow.
                    runCatching { socket.close() }
                }
                runCatching { socket.closeReason()?.code }.getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handshake = if (e is HandshakeRejectedException) e.status else handshakeStatus(e.message)
                null
            }
            // A session that delivered a frame proved itself; start the backoff ladder (and refusals) over.
            if (gotFrame) {
                attempt = 0
                refusals = 0
            }
            when {
                // Refused after the accept (archived/deleted board, auth revoked): retrying repeats it.
                closedCode == WS_1008_POLICY_VIOLATION -> return@flow
                // A login page in front of the upgrade or a gateway without the plugin never fixes itself.
                handshake != null && (handshake in 300..399 || handshake == 404) -> return@flow
                // The gate's pre-accept refusal: a few fresh tickets, like `GatewayConnection`, then give up.
                handshake == 403 && ++refusals >= REFUSAL_LIMIT -> return@flow
                else -> delay(backoffMs(++attempt))
            }
        }
    }

    private fun eventsUrl(url: GatewayUrl, board: String, ticket: String, cursor: Long?): String {
        val since = cursor?.let { "&since=$it" }.orEmpty()
        return url.siblingSocketUrl("api/plugins/kanban/events") +
            "?board=${board.encodeURLParameter()}&ticket=${ticket.encodeURLParameter()}$since"
    }

    private fun backoffMs(attempt: Int): Long = BACKOFF_MS[(attempt - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    private companion object {
        /** 1 s → 30 s, capped: transient mint/socket failures retry forever, but quietly. */
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)

        /** Fresh-ticket attempts against a 403 handshake before the tail gives up. */
        const val REFUSAL_LIMIT = 3
    }
}

/** Parses one socket frame; null for anything that isn't `{events, cursor}` (garbage never breaks the tail). */
internal fun parseEventsFrame(text: String): KanbanEventsFrame? =
    runCatching { Json.parseToJsonElement(text).let { HermesJson.decodeFromJsonElement(KanbanEventsFrame.serializer(), it) } }.getOrNull()
        ?.takeIf { it.events.isNotEmpty() || it.cursor > 0 }

/** FastAPI's `WS_1008_POLICY_VIOLATION`, closed when the upgrade or the pinned board is refused. */
private const val WS_1008_POLICY_VIOLATION: Short = 1008

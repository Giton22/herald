package dev.hermeskotlin.core.rpc

import dev.hermeskotlin.core.gateway.GatewayUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** WS close codes the dashboard uses (hermes_cli/web_routers/chat_ws.py). */
object GatewayCloseCodes {
    /** The ticket didn't authenticate (expired, reused, or the session is gone). */
    const val TICKET_REJECTED: Short = 4401

    /** Host/Origin/peer guard rejected the upgrade (DNS-rebinding protection). */
    const val REQUEST_GUARD: Short = 4403
}

/**
 * The WebSocket upgrade got a non-101 HTTP response. The dashboard rejects tickets (4401) and the
 * Host/Origin guard (4403) *before* accepting, which uvicorn turns into a plain HTTP 403, so a client
 * cannot tell those two apart from the status alone.
 */
class HandshakeRejectedException(val status: Int, cause: Throwable) :
    Exception("Gateway rejected the WebSocket upgrade (HTTP $status)", cause)

/** The socket closed. [code] is null when the connection dropped without a close frame. */
class TransportClosedException(val code: Short?, val reason: String?) :
    Exception("WebSocket closed (code=$code${reason?.let { ", $it" } ?: ""})")

/** A text-frame pipe. Abstracted so [JsonRpcClient] is testable without a network. */
interface RpcTransport {
    /** Inbound text frames. Completes normally never; ends by throwing [TransportClosedException]. */
    val incoming: Flow<String>

    suspend fun send(text: String)

    suspend fun close(code: Short = CloseReason.Codes.NORMAL.code, reason: String = "")
}

private class KtorWebSocketTransport(private val session: DefaultClientWebSocketSession) : RpcTransport {

    override val incoming: Flow<String> = flow {
        for (frame in session.incoming) {
            if (frame is Frame.Text) emit(frame.readText())
        }
        val reason = runCatching { session.closeReason.await() }.getOrNull()
        throw TransportClosedException(reason?.code, reason?.message?.ifBlank { null })
    }

    override suspend fun send(text: String) = session.send(Frame.Text(text))

    override suspend fun close(code: Short, reason: String) = session.close(CloseReason(code, reason))
}

private const val GATEWAY_PROTOCOL = "hermes-gateway-v1"
private const val TICKET_PROTOCOL_PREFIX = "hermes-gateway-ticket."

/**
 * Opens `/api/ws` the way the desktop does: the single-use ticket rides in the subprotocol list
 * (`hermes-gateway-v1, hermes-gateway-ticket.<ticket>`) so it never lands in URLs or logs.
 */
suspend fun HttpClient.openGatewaySocket(url: GatewayUrl, ticket: String): RpcTransport {
    val session = try {
        webSocketSession(url.webSocketUrl) {
            header(HttpHeaders.SecWebSocketProtocol, "$GATEWAY_PROTOCOL, $TICKET_PROTOCOL_PREFIX$ticket")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // OkHttp: java.net.ProtocolException "Expected HTTP 101 response but was '403 Forbidden'".
        val status = handshakeStatus(e.message)
        throw if (status != null) HandshakeRejectedException(status, e) else e
    }
    return KtorWebSocketTransport(session)
}

// OkHttp quotes the status line ("but was '403 Forbidden'"); Ktor's own check, which Darwin goes through, doesn't
// ("expected status code 101 but was 403").
private val HANDSHAKE_STATUS = Regex("""but was '?(\d{3})""")

internal fun handshakeStatus(message: String?): Int? =
    message?.let { HANDSHAKE_STATUS.find(it)?.groupValues?.get(1)?.toIntOrNull() }

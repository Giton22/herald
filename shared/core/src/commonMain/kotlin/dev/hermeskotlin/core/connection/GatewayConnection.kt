package dev.hermeskotlin.core.connection

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.rpc.GatewayCloseCodes
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.rpc.HandshakeRejectedException
import dev.hermeskotlin.core.rpc.HeartbeatTimeoutException
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.rpc.ServerRequest
import dev.hermeskotlin.core.rpc.TransportClosedException
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

sealed interface ConnectionState {
    data object Idle : ConnectionState

    data class Connecting(val attempt: Int) : ConnectionState

    data class Connected(val client: JsonRpcClient, val ready: JsonObject) : ConnectionState

    /** Lost or failed to connect; retrying at [retryAtMillis] (epoch ms). */
    data class Reconnecting(val attempt: Int, val retryAtMillis: Long, val reason: String) : ConnectionState

    /** The dashboard session is gone (refresh failed). The user must sign in again. */
    data object SessionExpired : ConnectionState

    /** Retrying won't help (e.g. 4403 Host/Origin guard). [retry] restarts manually. */
    data class Failed(val reason: String) : ConnectionState
}

/**
 * Keeps one authenticated `/api/ws` JSON-RPC connection alive for a gateway:
 * fresh WS ticket per attempt, exponential backoff, and terminal states for auth/guard failures.
 */
class GatewayConnection(
    private val auth: AuthApi,
    private val openSocket: suspend (GatewayUrl, String) -> RpcTransport,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { getTimeMillis() },
    private val readyTimeoutMs: Long = 15_000,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Server events from whichever socket is currently connected; silent while disconnected. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: Flow<GatewayEvent> = state.flatMapLatest { state ->
        (state as? ConnectionState.Connected)?.client?.events ?: emptyFlow()
    }

    /** Server→client requests from the connected socket; dropped while nothing collects them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val serverRequests: Flow<ServerRequest> = state.flatMapLatest { state ->
        (state as? ConnectionState.Connected)?.client?.serverRequests ?: emptyFlow()
    }

    private var job: Job? = null
    private var url: GatewayUrl? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start(gateway: GatewayUrl) {
        if (url == gateway && job?.isActive == true) return
        stop()
        url = gateway
        job = scope.launch { loop(gateway) }
    }

    fun stop() {
        job?.cancel()
        job = null
        (_state.value as? ConnectionState.Connected)?.client?.let { client -> scope.launch { runCatching { client.close() } } }
        _state.value = ConnectionState.Idle
    }

    /** Skip the current backoff wait, or restart after [ConnectionState.Failed]. */
    fun retry() {
        val current = url ?: return
        if (job?.isActive == true) wake.trySend(Unit) else start(current)
    }

    private suspend fun loop(gateway: GatewayUrl) {
        var attempt = 0
        var ticketRejections = 0
        while (true) {
            attempt++
            _state.value = ConnectionState.Connecting(attempt)

            val ticket = when (val result = auth.mintWsTicket(gateway)) {
                is ApiResult.Success -> result.value.ticket
                ApiResult.SessionExpired -> {
                    _state.value = ConnectionState.SessionExpired
                    return
                }
                is ApiResult.Unavailable -> null.also { backoff(attempt, "Can't reach gateway: ${result.message}") }
                ApiResult.RateLimited -> null.also { backoff(attempt, "Rate limited by gateway") }
                is ApiResult.Failed -> null.also { backoff(attempt, result.message) }
                ApiResult.InvalidCredentials -> null.also { backoff(attempt, "Not signed in") }
            } ?: continue

            var connectedAt: Long? = null
            val failure: Exception = try {
                runSession(openSocket(gateway, ticket)) { connectedAt = clock() }
            } catch (e: TimeoutCancellationException) {
                // The ready wait gives up as a cancellation; that is a failed attempt, not a cancelled loop.
                e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }

            // A connection that held for a while starts the backoff ladder (and ticket budget) over.
            if (connectedAt?.let { clock() - it > STABLE_CONNECTION_MS } == true) {
                attempt = 0
                ticketRejections = 0
            }

            when {
                failure is TransportClosedException && failure.code == GatewayCloseCodes.REQUEST_GUARD -> {
                    _state.value = ConnectionState.Failed(
                        "The gateway refused the connection (Host/Origin guard, 4403). " +
                            "Use the exact address the dashboard is configured for.",
                    )
                    return
                }
                failure is HandshakeRejectedException && failure.status == 403 -> {
                    // The ticket mint just succeeded, so a repeated 403 means the Host/Origin guard (or chat
                    // disabled on the dashboard), not an expired session. Retry with fresh tickets first.
                    if (++ticketRejections > 2) {
                        _state.value = ConnectionState.Failed(
                            "The gateway refused the chat connection (HTTP 403). Check that the address matches " +
                                "the dashboard's configured host and that embedded chat is enabled.",
                        )
                        return
                    }
                }
                failure is HandshakeRejectedException && failure.status == 404 -> {
                    _state.value = ConnectionState.Failed("This dashboard has no chat endpoint (/api/ws). Update Hermes.")
                    return
                }
                failure is TransportClosedException && failure.code == GatewayCloseCodes.TICKET_REJECTED -> {
                    // The ticket was minted moments ago; retry with a fresh one, then treat it as a lost session.
                    if (++ticketRejections > 2) {
                        _state.value = ConnectionState.SessionExpired
                        return
                    }
                }
                failure is HeartbeatTimeoutException -> backoff(maxOf(attempt, 1), "Gateway stopped responding")
                failure is TimeoutCancellationException -> backoff(maxOf(attempt, 1), "Gateway never said it was ready")
                failure is TransportClosedException -> backoff(maxOf(attempt, 1), failure.reason ?: "Connection lost")
                else -> backoff(maxOf(attempt, 1), failure.message ?: "Connection failed")
            }
        }
    }

    /**
     * Waits for `gateway.ready`, publishes [ConnectionState.Connected], then pumps until the socket
     * dies. Never returns normally.
     */
    private suspend fun runSession(transport: RpcTransport, onConnected: () -> Unit): Nothing = coroutineScope {
        val client = JsonRpcClient(transport, answers = InputRequest.METHODS::contains)
        val pump = async { client.run() }
        val ready = try {
            withTimeout(readyTimeoutMs) { client.ready.await() }
        } catch (e: Exception) {
            pump.cancel()
            runCatching { transport.close() }
            throw e
        }
        onConnected()
        _state.value = ConnectionState.Connected(client, ready)
        pump.await()
    }

    private suspend fun backoff(attempt: Int, reason: String) {
        val delayMs = BACKOFF_MS[(attempt - 1).coerceIn(0, BACKOFF_MS.lastIndex)]
        _state.value = ConnectionState.Reconnecting(attempt, clock() + delayMs, reason)
        withTimeoutOrNull(delayMs) { wake.receive() }
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
        const val STABLE_CONNECTION_MS = 30_000L
    }
}

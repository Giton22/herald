package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.rpc.GatewayCloseCodes
import dev.hermeskotlin.core.rpc.HandshakeRejectedException
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.rpc.TransportClosedException
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/** The three things a working gateway needs, checked one at a time and in this order. */
enum class CheckStage(val label: String) {
    Server("Server access"),
    SignIn("Sign-in"),
    Live("Live connection"),
}

sealed interface StageResult {
    data class Passed(val detail: String) : StageResult

    /** What went wrong, and the specific thing to do about it. */
    data class Failed(val problem: String, val fix: String) : StageResult

    /** Not run, because an earlier stage failed or this one needs a sign-in first. */
    data class Skipped(val reason: String) : StageResult
}

data class ConnectionReport(val results: Map<CheckStage, StageResult>) {
    /** The first stage that failed, which is where to start fixing. */
    val failedStage: CheckStage? get() = CheckStage.entries.firstOrNull { results[it] is StageResult.Failed }
}

/**
 * Checks a gateway stage by stage, so a failure names its stage: the server answering `/api/status`,
 * the stored sign-in (`/api/auth/me`), and the live chat connection (a WS ticket, the `/api/ws` upgrade
 * and `gateway.ready`). A reachable server says nothing about the other two, so each is tested itself.
 */
class ConnectionCheck(
    private val probe: GatewayProbe,
    private val auth: AuthApi,
    private val openSocket: suspend (GatewayUrl, String) -> RpcTransport,
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
) {

    /** Runs every stage in order; [onProgress] gets the report so far after each one. */
    suspend fun run(url: GatewayUrl, onProgress: (ConnectionReport) -> Unit = {}): ConnectionReport {
        val results = mutableMapOf<CheckStage, StageResult>()
        fun record(stage: CheckStage, result: StageResult) {
            results[stage] = result
            onProgress(ConnectionReport(results.toMap()))
        }
        val server = server(url).also { record(CheckStage.Server, it) }
        if (server !is StageResult.Passed) {
            record(CheckStage.SignIn, NEEDS_SERVER)
            record(CheckStage.Live, NEEDS_SERVER)
            return ConnectionReport(results.toMap())
        }
        val signIn = signIn(url).also { record(CheckStage.SignIn, it) }
        record(CheckStage.Live, if (signIn is StageResult.Passed) live(url) else StageResult.Skipped("Needs a sign-in first."))
        return ConnectionReport(results.toMap())
    }

    suspend fun server(url: GatewayUrl): StageResult = probe.probe(url).toStageResult()

    suspend fun signIn(url: GatewayUrl): StageResult = when (val result = auth.me(url)) {
        is ApiResult.Success -> StageResult.Passed("Signed in as ${result.value.label}.")
        ApiResult.SessionExpired, ApiResult.InvalidCredentials ->
            StageResult.Failed("This phone isn't signed in to the gateway, or the sign-in expired.", "Sign in again with your dashboard username and password.")
        ApiResult.RateLimited -> StageResult.Failed("The gateway is limiting sign-in attempts.", "Wait a minute, then try again.")
        is ApiResult.Unavailable -> StageResult.Failed("The sign-in check didn't get through: ${result.message}", reachFix(url))
        is ApiResult.Failed -> StageResult.Failed("The gateway turned the sign-in check down: ${result.message}", "Sign in again; if it keeps failing, update Hermes.")
    }

    suspend fun live(url: GatewayUrl): StageResult {
        val ticket = when (val result = auth.mintWsTicket(url)) {
            is ApiResult.Success -> result.value.ticket
            ApiResult.SessionExpired, ApiResult.InvalidCredentials ->
                return StageResult.Failed("The gateway wouldn't issue a chat ticket: the sign-in expired.", "Sign in again.")
            is ApiResult.Unavailable -> return StageResult.Failed("Couldn't ask for a chat ticket: ${result.message}", reachFix(url))
            ApiResult.RateLimited -> return StageResult.Failed("The gateway is limiting requests.", "Wait a minute, then try again.")
            is ApiResult.Failed -> return StageResult.Failed("The gateway wouldn't issue a chat ticket: ${result.message}", "Update Hermes, then try again.")
        }
        var transport: RpcTransport? = null
        return try {
            transport = openSocket(url, ticket)
            val client = JsonRpcClient(transport)
            coroutineScope {
                val pump = async { client.run() }
                try {
                    withTimeout(readyTimeoutMs) { client.ready.await() }
                } finally {
                    pump.cancel()
                }
            }
            StageResult.Passed("The chat connection opened and the gateway said it's ready.")
        } catch (e: TimeoutCancellationException) {
            StageResult.Failed(
                "The chat connection opened, but the gateway never said it was ready.",
                "Check that the agent gateway is running on the server. Behind a proxy, allow WebSocket upgrades on /api/ws.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            liveFailure(e)
        } finally {
            runCatching { transport?.close() }
        }
    }

    private fun liveFailure(e: Exception): StageResult.Failed = when {
        e is HandshakeRejectedException && e.status == 404 ->
            StageResult.Failed("This dashboard has no chat endpoint (/api/ws).", "Update Hermes on the server.")
        e is HandshakeRejectedException && e.status == 403 || e is TransportClosedException && e.code == GatewayCloseCodes.REQUEST_GUARD ->
            StageResult.Failed(
                "The gateway refused the chat connection (Host/Origin guard).",
                "Use the exact address the dashboard is configured for, and check that embedded chat is enabled.",
            )
        e is HandshakeRejectedException && e.status in 300..399 ->
            StageResult.Failed(
                "The chat connection was sent to a login page (HTTP ${e.status}).",
                "Behind Cloudflare Access, add a service token for this gateway. Otherwise check that the proxy forwards /api/ws.",
            )
        e is HandshakeRejectedException ->
            StageResult.Failed("The chat connection was refused (HTTP ${e.status}).", "Behind a proxy, allow WebSocket upgrades on /api/ws.")
        e is TransportClosedException && e.code == GatewayCloseCodes.TICKET_REJECTED ->
            StageResult.Failed("The gateway rejected the chat ticket.", "Sign in again.")
        else -> StageResult.Failed(
            "The chat connection failed: ${e.message ?: e::class.simpleName}",
            "Behind a proxy, allow WebSocket upgrades on /api/ws; otherwise try again.",
        )
    }

    private companion object {
        const val READY_TIMEOUT_MS = 10_000L
    }
}

/** The server-access stage as the probe found it. */
fun ProbeResult.toStageResult(): StageResult = when (this) {
    is ProbeResult.Reachable -> StageResult.Passed("Hermes ${status.version} answers at $url.")
    is ProbeResult.NotHermes -> StageResult.Failed(
        "Something answered at $url" + (httpStatus?.let { " (HTTP $it)" } ?: "") + ", but not a Hermes dashboard.",
        "Use the dashboard's own port, 9119 by default. Behind a proxy, check that it forwards to the dashboard.",
    )
    is ProbeResult.AccessBlocked -> if (tokenSent) StageResult.Failed(
        "Cloudflare Access turned down this gateway's service token (HTTP $httpStatus).",
        "Check the Client ID and Client Secret, and that the Access application has a Service Auth policy that includes this token.",
    ) else StageResult.Failed(
        "Cloudflare Access stopped the request before it reached Hermes (HTTP $httpStatus).",
        "Add a Cloudflare Access service token for this gateway, and give the Access application a Service Auth policy that includes it.",
    )
    is ProbeResult.Unreachable -> StageResult.Failed("Can't reach $url: $reason", reachFix(url))
}

/**
 * Every stage the probe alone can settle: server access, and when that fails, the later stages as
 * skipped. A passed server leaves sign-in and the live connection out, since they need a sign-in.
 */
fun ProbeResult.serverOnlyResults(): Map<CheckStage, StageResult> {
    val server = toStageResult()
    if (server is StageResult.Passed) return mapOf(CheckStage.Server to server)
    return mapOf(CheckStage.Server to server, CheckStage.SignIn to NEEDS_SERVER, CheckStage.Live to NEEDS_SERVER)
}

private val NEEDS_SERVER = StageResult.Skipped("Needs server access first.")

/** What to do when nothing answers, by the kind of address: this phone, Tailscale, HTTPS, or the local network. */
fun reachFix(url: GatewayUrl): String {
    val host = url.host.lowercase().trim('[', ']')
    val loopback = host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") || host == "::1"
    val tailscale = host.endsWith(".ts.net") || host.split('.').let { o -> o.size == 4 && o[0] == "100" && (o[1].toIntOrNull() ?: 0) in 64..127 }
    return when {
        loopback -> "This address points at this phone, not the server. Use the server's local network or Tailscale address instead."
        tailscale -> "Turn on Tailscale on this phone, and check that the server is online in the same tailnet."
        Url(url.value).protocol == URLProtocol.HTTPS -> "Check that the domain points to the server, its certificate is valid, and the proxy forwards to the dashboard."
        isPrivateHost(host) -> "Join the same Wi-Fi or network as the server, and start the dashboard with --host 0.0.0.0 so other devices can reach it."
        else -> "Check the address and port, and that the server is reachable from this phone."
    }
}

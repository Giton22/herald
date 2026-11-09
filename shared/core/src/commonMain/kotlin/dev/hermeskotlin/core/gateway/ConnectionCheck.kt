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
enum class CheckStage { Server, SignIn, Live }

/**
 * One line the check shows: [kind] says which, [args] fill it in (an address, a version, the gateway's or the
 * system's own message, shown as it is). The app turns it into words in its own language.
 */
data class CheckNote(val kind: Kind, val args: List<String> = emptyList()) {
    enum class Kind {
        // Passed: the server answers (version, address), signed in (who), the live connection is ready.
        ServerAnswers,
        SignedInAs,
        LiveReady,

        // Skipped: waiting on an earlier stage.
        NeedsServer,
        NeedsSignIn,

        // Problems.
        SignInExpired,
        SignInRateLimited,
        SignInUnavailable,
        SignInRefused,
        TicketExpired,
        TicketUnavailable,
        RequestsRateLimited,
        TicketRefused,
        NeverReady,
        NoChatEndpoint,
        ChatGuarded,
        ChatLoginRedirect,
        ChatRefused,
        TicketRejected,
        ChatCertRequired,
        ChatFailed,
        ServerCertRequired,
        NotHermes,
        NotHermesStatus,
        AccessTokenRefused,
        AccessBlocked,
        Unreachable,

        // Fixes.
        FixSignInAgain,
        FixSignInPasswordOrBrowser,
        FixWaitAMinute,
        FixSignInOrUpdate,
        FixUpdateHermesTryAgain,
        FixUpdateHermes,
        FixAgentRunning,
        FixExactAddress,
        FixAccessTokenOrProxy,
        FixAllowUpgrades,
        FixAllowUpgradesOrRetry,
        FixClientCert,
        FixDashboardPort,
        FixCheckAccessToken,
        FixAddAccessToken,
        FixLoopback,
        FixTailscale,
        FixHttps,
        FixLocalNetwork,
        FixReachable,
    }
}

private fun note(kind: CheckNote.Kind, vararg args: String) = CheckNote(kind, args.toList())

sealed interface StageResult {
    data class Passed(val detail: CheckNote) : StageResult

    /** What went wrong, and the specific thing to do about it. */
    data class Failed(val problem: CheckNote, val fix: CheckNote) : StageResult

    /** Not run, because an earlier stage failed or this one needs a sign-in first. */
    data class Skipped(val reason: CheckNote) : StageResult
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
        record(CheckStage.Live, if (signIn is StageResult.Passed) live(url) else StageResult.Skipped(note(CheckNote.Kind.NeedsSignIn)))
        return ConnectionReport(results.toMap())
    }

    suspend fun server(url: GatewayUrl): StageResult = probe.probe(url).toStageResult()

    suspend fun signIn(url: GatewayUrl): StageResult = when (val result = auth.me(url)) {
        is ApiResult.Success -> StageResult.Passed(note(CheckNote.Kind.SignedInAs, result.value.label))
        ApiResult.SessionExpired, ApiResult.InvalidCredentials ->
            StageResult.Failed(note(CheckNote.Kind.SignInExpired), note(CheckNote.Kind.FixSignInPasswordOrBrowser))
        ApiResult.RateLimited -> StageResult.Failed(note(CheckNote.Kind.SignInRateLimited), note(CheckNote.Kind.FixWaitAMinute))
        is ApiResult.Unavailable -> StageResult.Failed(note(CheckNote.Kind.SignInUnavailable, result.message), reachFix(url))
        is ApiResult.Failed -> StageResult.Failed(note(CheckNote.Kind.SignInRefused, result.message), note(CheckNote.Kind.FixSignInOrUpdate))
    }

    suspend fun live(url: GatewayUrl): StageResult {
        val ticket = when (val result = auth.mintWsTicket(url)) {
            is ApiResult.Success -> result.value.ticket
            ApiResult.SessionExpired, ApiResult.InvalidCredentials ->
                return StageResult.Failed(note(CheckNote.Kind.TicketExpired), note(CheckNote.Kind.FixSignInAgain))
            is ApiResult.Unavailable -> return StageResult.Failed(note(CheckNote.Kind.TicketUnavailable, result.message), reachFix(url))
            ApiResult.RateLimited -> return StageResult.Failed(note(CheckNote.Kind.RequestsRateLimited), note(CheckNote.Kind.FixWaitAMinute))
            is ApiResult.Failed -> return StageResult.Failed(note(CheckNote.Kind.TicketRefused, result.message), note(CheckNote.Kind.FixUpdateHermesTryAgain))
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
            StageResult.Passed(note(CheckNote.Kind.LiveReady))
        } catch (e: TimeoutCancellationException) {
            StageResult.Failed(note(CheckNote.Kind.NeverReady), note(CheckNote.Kind.FixAgentRunning))
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
            StageResult.Failed(note(CheckNote.Kind.NoChatEndpoint), note(CheckNote.Kind.FixUpdateHermes))
        e is HandshakeRejectedException && e.status == 403 || e is TransportClosedException && e.code == GatewayCloseCodes.REQUEST_GUARD ->
            StageResult.Failed(note(CheckNote.Kind.ChatGuarded), note(CheckNote.Kind.FixExactAddress))
        e is HandshakeRejectedException && e.status in 300..399 ->
            StageResult.Failed(note(CheckNote.Kind.ChatLoginRedirect, e.status.toString()), note(CheckNote.Kind.FixAccessTokenOrProxy))
        e is HandshakeRejectedException ->
            StageResult.Failed(note(CheckNote.Kind.ChatRefused, e.status.toString()), note(CheckNote.Kind.FixAllowUpgrades))
        e is TransportClosedException && e.code == GatewayCloseCodes.TICKET_REJECTED ->
            StageResult.Failed(note(CheckNote.Kind.TicketRejected), note(CheckNote.Kind.FixSignInAgain))
        isClientCertDemand(e) ->
            StageResult.Failed(note(CheckNote.Kind.ChatCertRequired, e.message ?: e::class.simpleName.orEmpty()), CLIENT_CERT_FIX)
        else -> StageResult.Failed(
            note(CheckNote.Kind.ChatFailed, e.message ?: e::class.simpleName.orEmpty()),
            note(CheckNote.Kind.FixAllowUpgradesOrRetry),
        )
    }

    private companion object {
        const val READY_TIMEOUT_MS = 10_000L
    }
}

/** The server-access stage as the probe found it. */
fun ProbeResult.toStageResult(): StageResult = when (this) {
    is ProbeResult.Reachable -> StageResult.Passed(note(CheckNote.Kind.ServerAnswers, status.version.toString(), url.toString()))
    is ProbeResult.ClientCertRequired -> StageResult.Failed(note(CheckNote.Kind.ServerCertRequired, url.toString(), reason), CLIENT_CERT_FIX)
    is ProbeResult.NotHermes -> StageResult.Failed(
        httpStatus?.let { note(CheckNote.Kind.NotHermesStatus, url.toString(), it.toString()) } ?: note(CheckNote.Kind.NotHermes, url.toString()),
        note(CheckNote.Kind.FixDashboardPort),
    )
    is ProbeResult.AccessBlocked -> if (tokenSent) {
        StageResult.Failed(note(CheckNote.Kind.AccessTokenRefused, httpStatus.toString()), note(CheckNote.Kind.FixCheckAccessToken))
    } else {
        StageResult.Failed(note(CheckNote.Kind.AccessBlocked, httpStatus.toString()), note(CheckNote.Kind.FixAddAccessToken))
    }
    is ProbeResult.Unreachable -> StageResult.Failed(note(CheckNote.Kind.Unreachable, url.toString(), reason), reachFix(url))
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

private val NEEDS_SERVER = StageResult.Skipped(note(CheckNote.Kind.NeedsServer))

/** What to do when the server wants a client certificate before it talks. */
private val CLIENT_CERT_FIX = note(CheckNote.Kind.FixClientCert)

/** What to do when nothing answers, by the kind of address: this phone, Tailscale, HTTPS, or the local network. */
fun reachFix(url: GatewayUrl): CheckNote {
    val host = url.host.lowercase().trim('[', ']')
    val loopback = host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") || host == "::1"
    val tailscale = host.endsWith(".ts.net") || host.split('.').let { o -> o.size == 4 && o[0] == "100" && (o[1].toIntOrNull() ?: 0) in 64..127 }
    return note(
        when {
            loopback -> CheckNote.Kind.FixLoopback
            tailscale -> CheckNote.Kind.FixTailscale
            Url(url.value).protocol == URLProtocol.HTTPS -> CheckNote.Kind.FixHttps
            isPrivateHost(host) -> CheckNote.Kind.FixLocalNetwork
            else -> CheckNote.Kind.FixReachable
        },
    )
}

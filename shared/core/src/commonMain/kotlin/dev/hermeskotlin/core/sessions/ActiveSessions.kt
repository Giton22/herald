package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.chat.string
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.Volatile

/** What a live session on the gateway is doing, from `session.active_list`. */
enum class LiveStatus {
    Idle,

    /**
     * The agent is still being built. Not a turn: every cold `session.resume` builds one straight away (a chat
     * opened here, on Desktop, a bot chat watched), and the gateway reports this over a turn's own `running`.
     */
    Starting,

    /** A server request (approval, question, secret…) waits for an answer. The row doesn't say which kind. */
    Waiting,
    Working,
    ;

    /**
     * A turn is under way: it may be blocked on the user, but it hasn't ended. [Starting] can't tell a turn
     * from a chat just opened, so it counts as none; a turn on a cold chat shows once its agent is built.
     */
    val running: Boolean get() = this == Working || this == Waiting

    companion object {
        /** `streaming` and `resuming` are in the contract but no handler sets them yet; both are a turn going. */
        fun parse(value: String?): LiveStatus = when (value) {
            "starting" -> Starting
            "waiting" -> Waiting
            "working", "streaming", "resuming" -> Working
            else -> Idle
        }
    }
}

/** One live session: its runtime id on the gateway, what it's doing, and its title (blank before it has one). */
data class LiveSession(val runtimeId: String, val status: LiveStatus, val title: String = "")

/** The gateway's live sessions by stored session id, as asked at [askedAtMillis] (epoch ms; 0 before any answer). */
data class LiveSessions(val rows: Map<String, LiveSession> = emptyMap(), val askedAtMillis: Long = 0) {
    val statuses: Map<String, LiveStatus> get() = rows.mapValues { it.value.status }
}

/**
 * The status of every live session on the gateway, turns started on other clients included: the list's
 * Running and Needs-attention marks for chats this phone never opened. No event announces a change, so it
 * asks `session.active_list` on each connection, again shortly after an event that may mean a change, and
 * every [pollMs] while something collects [live] ([backgroundPollMs] while [inBackground]). It stops asking
 * when nothing does.
 *
 * The answer covers every profile in the gateway process (the handler ignores `profile`), keyed by stored
 * id: callers match it against the chats they list. Empty while disconnected, so nothing old shows as live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSessions(
    private val connection: GatewayConnection,
    scope: CoroutineScope,
    private val pollMs: Long = 10_000,
    private val settleMs: Long = 500,
    private val clock: () -> Long = { getTimeMillis() },
    private val backgroundPollMs: Long = 30_000,
) {
    /** Herald is out of sight: nobody reads the list, only notifications need the statuses, so ask less often. */
    @Volatile
    var inBackground: Boolean = false
        set(value) {
            val back = field && !value
            field = value
            // The answer can be a slow wait old, and the watcher keeps the poll going, so nothing restarts it: ask now.
            if (back) wakes.trySend(Unit)
        }

    /** Ends the wait between asks when Herald comes back in sight. */
    private val wakes = Channel<Unit>(Channel.CONFLATED)

    val live: StateFlow<LiveSessions> = connection.state
        .map { (it as? ConnectionState.Connected)?.client }
        .distinctUntilChanged()
        .flatMapLatest { client -> client?.let(::poll) ?: flowOf(LiveSessions()) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), LiveSessions())

    // No empty answer first: collecting again (the sidebar opening) would blank every live mark until the
    // gateway answers. A link dropped while collected clears them on the way (no client); otherwise the last
    // answer stands for that moment, and its time lets newer turn events beat it.
    private fun poll(client: JsonRpcClient): Flow<LiveSessions> = channelFlow {
        val nudges = Channel<Unit>(Channel.CONFLATED)
        launch { client.events.collect { if (it.type in CHANGES) nudges.trySend(Unit) } }
        while (true) {
            val askedAt = clock()
            val rows = try {
                parse(client.request("session.active_list"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                // A gateway without the method won't grow one on this socket: keep what's shown, stop asking.
                if (e.code == METHOD_NOT_FOUND) awaitCancellation()
                null
            } catch (e: Exception) {
                // A timeout or a dropped reply: the last answer stands until the next ask.
                null
            }
            if (rows != null) send(LiveSessions(rows, askedAt))
            val nudged = withTimeoutOrNull(if (inBackground) backgroundPollMs else pollMs) {
                select { nudges.onReceive { true }; wakes.onReceive { false } }
            }
            if (nudged == true) {
                // Events come in bursts (a turn's start, its end, the list changing): ask once they settle.
                delay(settleMs)
                nudges.tryReceive()
            }
        }
    }

    private fun parse(result: JsonElement): Map<String, LiveSession> =
        ((result as? JsonObject)?.get("sessions") as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = runCatching { row.jsonObject }.getOrNull() ?: return@mapNotNull null
            val key = obj.string("session_key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val runtimeId = obj.string("id")?.takeIf { it.isNotBlank() } ?: key
            key to LiveSession(runtimeId, LiveStatus.parse(obj.string("status")), obj.string("title").orEmpty())
        }.toMap()

    private companion object {
        /** Events after which a status may have changed. */
        val CHANGES = setOf("sessions.changed", "message.start", "message.complete")
        const val METHOD_NOT_FOUND = -32601

        /** Brief gaps (a screen rotating) keep the poll going. */
        const val STOP_AFTER_MS = 5_000L
    }
}

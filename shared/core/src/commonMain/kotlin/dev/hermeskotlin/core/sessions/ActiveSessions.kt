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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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

/** The gateway's live sessions by stored session id, as asked at [askedAtMillis] (epoch ms; 0 before any answer). */
data class LiveSessions(val statuses: Map<String, LiveStatus> = emptyMap(), val askedAtMillis: Long = 0)

/**
 * The status of every live session on the gateway, turns started on other clients included: the list's
 * Running and Needs-attention marks for chats this phone never opened. No event announces a change, so it
 * asks `session.active_list` on each connection, again shortly after an event that may mean a change, and
 * every [pollMs] while something collects [live]. It stops asking when nothing does.
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
) {
    val live: StateFlow<LiveSessions> = connection.state
        .map { (it as? ConnectionState.Connected)?.client }
        .distinctUntilChanged()
        .flatMapLatest { client -> client?.let(::poll) ?: flowOf(LiveSessions()) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), LiveSessions())

    private fun poll(client: JsonRpcClient): Flow<LiveSessions> = channelFlow {
        send(LiveSessions())
        val nudges = Channel<Unit>(Channel.CONFLATED)
        launch { client.events.collect { if (it.type in CHANGES) nudges.trySend(Unit) } }
        while (true) {
            val askedAt = clock()
            val statuses = try {
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
            if (statuses != null) send(LiveSessions(statuses, askedAt))
            if (withTimeoutOrNull(pollMs) { nudges.receive() } != null) {
                // Events come in bursts (a turn's start, its end, the list changing): ask once they settle.
                delay(settleMs)
                nudges.tryReceive()
            }
        }
    }

    private fun parse(result: JsonElement): Map<String, LiveStatus> =
        ((result as? JsonObject)?.get("sessions") as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = runCatching { row.jsonObject }.getOrNull() ?: return@mapNotNull null
            val key = obj.string("session_key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to LiveStatus.parse(obj.string("status"))
        }.toMap()

    private companion object {
        /** Events after which a status may have changed. */
        val CHANGES = setOf("sessions.changed", "message.start", "message.complete")
        const val METHOD_NOT_FOUND = -32601

        /** Brief gaps (a screen rotating) keep the poll going. */
        const val STOP_AFTER_MS = 5_000L
    }
}

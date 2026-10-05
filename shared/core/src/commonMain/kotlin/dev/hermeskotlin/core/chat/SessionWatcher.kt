package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.rpc.ServerRequest
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.LiveSession
import dev.hermeskotlin.core.sessions.LiveSessions
import dev.hermeskotlin.core.sessions.LiveStatus
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A chat this phone watches without having it open: what it's called, and the requests it waits on.
 * [arrivals] says when each request came (epoch ms), so one answered on another client can be dropped.
 */
data class WatchedChat(
    val storedId: String,
    val runtimeId: String,
    val title: String,
    val requests: List<InputRequest> = emptyList(),
    internal val arrivals: Map<String, Long> = emptyMap(),
)

/** A watched chat's turn ended: its reply's [text], or [error] when it failed. */
data class WatchedTurnEnd(val storedId: String, val title: String, val text: String, val outcome: TurnOutcome, val error: String?)

/**
 * Follows the chats running on the gateway that this phone doesn't have open, so their approvals,
 * questions and finished replies can notify. The gateway sends a session's events and requests only to
 * the sockets attached to it, so each chat that [ActiveSessions] lists as running is attached with
 * `session.activate`. That call attaches only to a live session, by its runtime id: it never builds a
 * session, and another client attached to it keeps its own events (the gateway fans them out).
 *
 * It never calls `session.close`, which would end the session for every client, and the gateway has no
 * way to detach one socket. So a chat stays watched until the socket closes, and at most [maxWatched] are
 * attached on one socket. Skipped: the open chat (it notifies for itself) and bot chats (bot messages
 * notify through `sessions.changed`).
 *
 * An attached socket also keeps the session loaded: the gateway unloads a session only once no socket is
 * on it. So chats are attached only while [follow] is on, that is while something would notify for them;
 * in sight, the list's live statuses come from [ActiveSessions] alone.
 *
 * Works only while the socket is up: in sight, while a turn runs, or with Notifications anywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionWatcher(
    private val connection: GatewayConnection,
    private val host: ChatHost,
    private val active: ActiveSessions,
    private val attention: AttentionTracker,
    scope: CoroutineScope,
    private val maxWatched: Int = MAX_WATCHED,
    private val clock: () -> Long = { getTimeMillis() },
) {
    private val _chats = MutableStateFlow<Map<String, WatchedChat>>(emptyMap())

    /** The watched chats by stored session id. */
    val chats: StateFlow<Map<String, WatchedChat>> = _chats.asStateFlow()

    private val _turnEnds = MutableSharedFlow<WatchedTurnEnd>(extraBufferCapacity = 16)
    val turnEnds: SharedFlow<WatchedTurnEnd> = _turnEnds.asSharedFlow()

    private val _turnStarts = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** A watched chat's stored id each time a turn starts there: its last reply is no longer the news. */
    val turnStarts: SharedFlow<String> = _turnStarts.asSharedFlow()

    private val following = MutableStateFlow(false)

    /**
     * Whether running chats are attached now; off until a notifier turns it on. Off, none is newly attached,
     * and those attached stay (no detach).
     */
    fun follow(on: Boolean) {
        following.value = on
    }

    init {
        scope.launch {
            connection.state.map { state ->
                when (state) {
                    is ConnectionState.Connected -> state.client
                    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> BETWEEN_SOCKETS
                    else -> null
                }
            }.distinctUntilChanged().collectLatest { link ->
                when (link) {
                    // A new socket is attached to nothing, so each chat is attached again. What was watched stays
                    // meanwhile: a request still waiting keeps its notification (no second heads-up), and a reply
                    // still reaches a chat whose turn ended while the socket was down.
                    is JsonRpcClient -> watch(link)
                    BETWEEN_SOCKETS -> Unit
                    // Signed out, or nothing to retry: nothing is watched.
                    else -> _chats.value = emptyMap()
                }
            }
        }
        scope.launch {
            // Both hear a watched chat's requests once it's open, but only the open chat sees its answer there.
            // Gone from the chat still open means answered (or withdrawn); switching chats isn't an answer.
            var shownIn: String? = null
            var shown = emptySet<String>()
            host.session.flatMapLatest { it?.state ?: flowOf(null) }.collect { state ->
                val storedId = state?.storedSessionId
                val now = state?.inputRequests.orEmpty().mapTo(HashSet()) { it.id }
                if (storedId != null && storedId == shownIn) (shown - now).forEach(::drop)
                shownIn = storedId
                shown = now
            }
        }
    }

    /** The watched chat waiting on [requestId], with the request. */
    fun find(requestId: String): Pair<WatchedChat, InputRequest>? =
        _chats.value.values.firstNotNullOfOrNull { chat -> chat.requests.firstOrNull { it.id == requestId }?.let { chat to it } }

    /** Sends [result] as the answer to [requestId] (see [InputAnswers]). False when it couldn't go out. */
    suspend fun answer(requestId: String, result: JsonObject): Boolean {
        val client = connectedClient() ?: return false
        return try {
            client.respond(requestId, result)
            drop(requestId)
            attention.answered(requestId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Sends [text] as the next prompt in the watched chat [storedId]. False when it isn't watched or couldn't go out. */
    suspend fun reply(storedId: String, text: String): Boolean {
        val chat = _chats.value[storedId] ?: return false
        val client = connectedClient() ?: return false
        return try {
            client.request(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", chat.runtimeId)
                    put("text", text)
                },
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun watch(client: JsonRpcClient): Unit = coroutineScope {
        launch { client.serverRequests.collect(::onRequest) }
        launch { client.events.collect(::onEvent) }
        // Stored ids attached (or being attached) on this socket. Only this collector touches it.
        val attached = mutableSetOf<String>()
        combine(active.live, following) { live, on -> live to on }.collect { (live, on) ->
            val openId = host.session.value?.state?.value?.storedSessionId
            for ((storedId, row) in live.rows) {
                if (!row.status.running || storedId == openId || row.title == BotsApi.BOT_CHAT_TITLE) continue
                // Compressing a chat goes on in a new stored session on the same runtime: the same chat, renamed.
                _chats.value.values.firstOrNull { it.runtimeId == row.runtimeId && it.storedId != storedId }?.let { old ->
                    _chats.update { all -> all[old.storedId]?.let { all - old.storedId + (storedId to it.copy(storedId = storedId)) } ?: all }
                    attention.watched(row.runtimeId, storedId, row.status.running, emptyList())
                    if (attached.remove(old.storedId)) attached += storedId
                }
                // Read for each chat: following may stop while an earlier attach waits on its reply.
                if (!on || !following.value || storedId in attached || attached.size >= maxWatched) continue
                attached += storedId
                when (attach(client, storedId, row)) {
                    Attach.Done -> Unit
                    Attach.Failed -> attached -= storedId
                    // A gateway without the call can't be watched on: stop here, keep the socket.
                    Attach.Unsupported -> {
                        _chats.value = emptyMap()
                        awaitCancellation()
                    }
                }
            }
            dropAnsweredElsewhere(live)
        }
    }

    private suspend fun attach(client: JsonRpcClient, storedId: String, row: LiveSession): Attach {
        val since = clock()
        val known = storedId in _chats.value
        // Known before the call, so a request sent while it attaches already finds its chat. One watched on an
        // earlier socket keeps its requests until the reply says which still wait.
        _chats.update { all ->
            val chat = all[storedId]?.let { it.copy(runtimeId = row.runtimeId, title = row.title.ifBlank { it.title }) }
            all + (storedId to (chat ?: WatchedChat(storedId, row.runtimeId, row.title)))
        }
        val result = try {
            client.request(
                "session.activate",
                buildJsonObject {
                    put("session_id", row.runtimeId)
                    put("omit_messages", true)
                },
            ) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            _chats.update { it - storedId }
            return if (e.code == METHOD_NOT_FOUND) Attach.Unsupported else Attach.Failed
        } catch (e: Exception) {
            null
        }
        if (result == null) {
            // A timeout says nothing either way: one watched before keeps what it had, and the next list decides.
            if (!known) _chats.update { it - storedId }
            return Attach.Failed
        }
        val runtimeId = result.string("session_id") ?: row.runtimeId
        // Requests asked before this socket was attached: the gateway keeps them open and hands them over.
        val open = result["open_requests"].asObjectList().mapNotNull { snapshot ->
            InputRequest.parse(
                id = snapshot.string("id") ?: return@mapNotNull null,
                method = snapshot.string("method") ?: return@mapNotNull null,
                params = snapshot["params"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
        val now = clock()
        _chats.update { all ->
            val chat = all[storedId] ?: return@update all
            // The reply lists every request still open. One heard on this socket since the call went out is as
            // new; one from an earlier socket that the reply leaves out was answered while that socket was down.
            val heard = chat.requests.filter { request -> (chat.arrivals[request.id] ?: 0) >= since && open.none { it.id == request.id } }
            val requests = open + heard
            all + (storedId to chat.copy(runtimeId = runtimeId, requests = requests, arrivals = requests.associate { it.id to (chat.arrivals[it.id] ?: now) }))
        }
        val running = result.boolean("running") == true
        attention.watched(runtimeId, storedId, running, open)
        // A turn that started while this socket wasn't attached (an idle chat isn't attached again).
        if (running) _turnStarts.emit(storedId)
        // The list showed it running, but the turn ended before the attach: its end never reaches this socket.
        // The reply's text isn't at hand, so it's told without it.
        else if (row.status.running) {
            val title = _chats.value[storedId]?.title ?: row.title
            _turnEnds.emit(WatchedTurnEnd(storedId, title, "", TurnOutcome.Complete, null))
        }
        return Attach.Done
    }

    private fun onRequest(request: ServerRequest) {
        val runtimeId = request.sessionId ?: return
        val parsed = InputRequest.parse(request.id, request.method, request.params) ?: return
        val now = clock()
        _chats.update { all ->
            val chat = all.values.firstOrNull { it.runtimeId == runtimeId } ?: return@update all
            if (chat.requests.any { it.id == parsed.id }) return@update all
            all + (chat.storedId to chat.copy(requests = chat.requests + parsed, arrivals = chat.arrivals + (parsed.id to now)))
        }
    }

    private suspend fun onEvent(event: GatewayEvent) {
        val runtimeId = event.sessionId ?: return
        val chat = _chats.value.values.firstOrNull { it.runtimeId == runtimeId } ?: return
        val payload = event.payload as? JsonObject
        when (event.type) {
            "request.cancel", "approval.cancelled" -> (payload.string("id") ?: payload.string("request_id"))?.let(::drop)
            "session.title" -> payload.string("title")?.takeIf { it.isNotBlank() }?.let { title ->
                _chats.update { all -> all[chat.storedId]?.let { all + (chat.storedId to it.copy(title = title)) } ?: all }
            }
            "message.start" -> _turnStarts.emit(chat.storedId)
            "message.complete" -> {
                // Nothing can still wait once the turn is over.
                _chats.update { all -> all[chat.storedId]?.let { all + (chat.storedId to it.copy(requests = emptyList(), arrivals = emptyMap())) } ?: all }
                val outcome = when (payload.string("status")) {
                    "error" -> TurnOutcome.Error
                    "interrupted" -> TurnOutcome.Interrupted
                    else -> TurnOutcome.Complete
                }
                val error = payload.string("error") ?: payload.string("failure_reason")
                val title = _chats.value[chat.storedId]?.title ?: chat.title
                _turnEnds.emit(WatchedTurnEnd(chat.storedId, title, payload.string("text").orEmpty(), outcome, error))
            }
        }
    }

    /**
     * No event says that another client answered a request. A list asked after it came that no longer shows
     * the chat waiting means it was answered (or withdrawn) elsewhere.
     */
    private fun dropAnsweredElsewhere(live: LiveSessions) = _chats.update { all ->
        all.mapValues { (storedId, chat) ->
            if (live.rows[storedId]?.status == LiveStatus.Waiting) return@mapValues chat
            val gone = chat.requests.filter { (chat.arrivals[it.id] ?: Long.MAX_VALUE) < live.askedAtMillis }.mapTo(HashSet()) { it.id }
            if (gone.isEmpty()) chat else chat.copy(requests = chat.requests.filterNot { it.id in gone }, arrivals = chat.arrivals - gone)
        }
    }

    private fun drop(requestId: String) = _chats.update { all ->
        all.mapValues { (_, chat) ->
            if (chat.requests.none { it.id == requestId }) chat else chat.copy(requests = chat.requests.filterNot { it.id == requestId }, arrivals = chat.arrivals - requestId)
        }
    }

    private fun connectedClient(): JsonRpcClient? = (connection.state.value as? ConnectionState.Connected)?.client

    private enum class Attach { Done, Failed, Unsupported }

    private companion object {
        /** Chats attached on one socket at most. With no way to detach, each stays until the socket closes. */
        const val MAX_WATCHED = 10
        const val METHOD_NOT_FOUND = -32601

        /** Connecting or retrying: no socket, but one is on its way. */
        val BETWEEN_SOCKETS = Any()
    }
}

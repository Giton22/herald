package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.LiveStatus
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** What a chat is waiting on the user for, in words: the list shows it as text, not only as a colour. */
enum class Waiting(val label: String) {
    Approval("Needs approval"),
    Question("Has a question"),
    Input("Needs input"),

    /** The gateway says the chat waits on an answer, but this phone hasn't seen the request (it went to another client). */
    Unknown("Waiting for you"),
    ;

    companion object {
        fun of(request: InputRequest): Waiting = when (request) {
            is InputRequest.Approval -> Approval
            is InputRequest.Clarify -> Question
            is InputRequest.Secret, is InputRequest.VaultSaveLogin -> Input
        }
    }
}

/** An open request: the runtime session it's for, and when it arrived (epoch ms). */
private data class OpenRequest(val runtimeId: String, val request: InputRequest, val atMillis: Long)

/** Whether a runtime session's turn is running, as of [atMillis] (epoch ms). */
private data class Turn(val running: Boolean, val atMillis: Long)

/**
 * The chats waiting on the user, and the chats with a turn running, by stored session id. Two sources feed
 * it. The first is what this phone hears itself: requests and turn events arrive by runtime session id, so
 * each chat's ids are linked as it attaches; a request stays until it's answered, withdrawn
 * (`request.cancel`) or its turn ends. The second is the gateway's own list of live sessions
 * ([ActiveSessions]), which also covers chats that only other clients opened. Where the two disagree, the
 * newer one wins. The open chat speaks for itself: its own requests are the ones it still shows.
 *
 * Both maps ask the gateway only while something collects them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttentionTracker(
    connection: GatewayConnection,
    host: ChatHost,
    active: ActiveSessions,
    scope: CoroutineScope,
    private val clock: () -> Long = { getTimeMillis() },
) {

    /** Runtime session id to stored session id, for every chat opened while the app runs. */
    private val links = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Open requests by id. */
    private val requests = MutableStateFlow<Map<String, OpenRequest>>(emptyMap())

    private val open: StateFlow<ChatState?> = host.session
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val waiting: StateFlow<Map<String, Waiting>> = combine(links, requests, open, active.live) { links, requests, open, live ->
        val openId = open?.storedSessionId
        buildMap {
            // Newest first: an answer from another client sends no `request.cancel`, so an older request for the
            // same chat may be one already answered (an approval, then the question that followed it).
            requests.values.reversed().forEach { (runtimeId, request, at) ->
                val storedId = links[runtimeId] ?: return@forEach
                // Asked after the request came and not waiting: answered elsewhere while this phone missed the word.
                val stale = live.askedAtMillis > at && live.statuses[storedId] != LiveStatus.Waiting
                if (storedId != openId && storedId !in this && !stale) put(storedId, Waiting.of(request))
            }
            live.statuses.forEach { (storedId, status) ->
                if (status == LiveStatus.Waiting && storedId != openId && storedId !in this) put(storedId, Waiting.Unknown)
            }
            if (openId != null) open.inputRequests.firstOrNull()?.let { put(openId, Waiting.of(it)) }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), emptyMap())

    /** Whether a turn is running, by runtime session id, from each `message.start` and `message.complete`. */
    private val turns = MutableStateFlow<Map<String, Turn>>(emptyMap())

    /**
     * Whether each chat has a turn running, by stored session id. A chat neither source knows is left out:
     * the list's `is_active` can't stand in, since the gateway keeps it set while a session stays loaded,
     * long after its turn ends. A turn event heard after the last ask beats the gateway's answer, and an
     * answer newer than the event beats the event (a `message.complete` missed while offline). The open chat
     * speaks for itself, turns started elsewhere included.
     */
    val running: StateFlow<Map<String, Boolean>> = combine(links, turns, open, active.live) { links, turns, open, live ->
        buildMap {
            live.statuses.forEach { (storedId, status) -> put(storedId, status.running) }
            turns.forEach { (runtimeId, turn) ->
                val storedId = links[runtimeId] ?: return@forEach
                if (turn.atMillis >= live.askedAtMillis) put(storedId, turn.running)
                // The gateway was asked after this event and doesn't list the session: it isn't live, so idle.
                else if (storedId !in live.statuses) put(storedId, false)
            }
            open?.storedSessionId?.let { put(it, open.running) }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), emptyMap())

    /**
     * A chat this phone attached to without opening it ([SessionWatcher]): its requests and turns now count
     * for [storedId]. [running] and [openRequests] come from the attach reply, which also returns the
     * requests asked before this socket was attached.
     */
    fun watched(runtimeId: String, storedId: String, running: Boolean, openRequests: List<InputRequest>) {
        val now = clock()
        links.update { it + (runtimeId to storedId) }
        turns.update { it + (runtimeId to Turn(running, now)) }
        if (openRequests.isNotEmpty()) requests.update { all -> all + openRequests.associate { it.id to OpenRequest(runtimeId, it, now) } }
    }

    /** [requestId] was answered from outside the open chat (a notification): it no longer waits. */
    fun answered(requestId: String) = requests.update { it - requestId }

    init {
        scope.launch {
            connection.serverRequests.collect { request ->
                val runtimeId = request.sessionId ?: return@collect
                val parsed = InputRequest.parse(request.id, request.method, request.params) ?: return@collect
                requests.update { it + (parsed.id to OpenRequest(runtimeId, parsed, clock())) }
            }
        }
        scope.launch {
            connection.events.collect { event ->
                when (event.type) {
                    "request.cancel", "approval.cancelled" -> {
                        val id = (event.payload as? JsonObject).string("id") ?: (event.payload as? JsonObject).string("request_id")
                        if (id != null) requests.update { it - id }
                    }
                    "message.start" -> event.sessionId?.let { runtimeId -> turns.update { it + (runtimeId to Turn(true, clock())) } }
                    // Nothing can still wait once the turn is over.
                    "message.complete" -> event.sessionId?.let { runtimeId ->
                        turns.update { it + (runtimeId to Turn(false, clock())) }
                        requests.update { all -> all.filterValues { it.runtimeId != runtimeId } }
                    }
                }
            }
        }
        scope.launch {
            var shownIn: String? = null
            var shown = emptySet<String>()
            open.collect { state ->
                val runtimeId = state?.runtimeSessionId
                val storedId = state?.storedSessionId
                if (runtimeId != null && storedId != null && links.value[runtimeId] != storedId) {
                    links.update { it + (runtimeId to storedId) }
                }
                // Kept for after the user leaves it: the open chat knows, from its resume, of turns begun elsewhere.
                if (runtimeId != null && state.running != turns.value[runtimeId]?.running) {
                    turns.update { it + (runtimeId to Turn(state.running, clock())) }
                }
                // Gone from the chat still open means answered here (or elsewhere): it no longer waits.
                // Switching chats isn't an answer, so only the same chat's requests are compared.
                val now = state?.inputRequests.orEmpty().mapTo(HashSet()) { it.id }
                if (storedId != null && storedId == shownIn) {
                    val answered = shown - now
                    if (answered.isNotEmpty()) requests.update { it - answered }
                }
                shownIn = storedId
                shown = now
            }
        }
    }
}

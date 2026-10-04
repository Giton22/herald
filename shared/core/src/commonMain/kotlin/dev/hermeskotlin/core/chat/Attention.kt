package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.GatewayConnection
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
    ;

    companion object {
        fun of(request: InputRequest): Waiting = when (request) {
            is InputRequest.Approval -> Approval
            is InputRequest.Clarify -> Question
            is InputRequest.Secret -> Input
        }
    }
}

/**
 * The chats waiting on the user, by stored session id, across every chat this phone has heard from, not
 * just the open one. Requests arrive by runtime session id, so the open chat's ids are linked as it
 * attaches; a request stays until it's answered, withdrawn (`request.cancel`) or its turn ends. The open
 * chat speaks for itself: its own requests are the ones it still shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttentionTracker(connection: GatewayConnection, host: ChatHost, scope: CoroutineScope) {

    /** Runtime session id to stored session id, for every chat opened while the app runs. */
    private val links = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Open requests by id, with the runtime session they're for. */
    private val requests = MutableStateFlow<Map<String, Pair<String, InputRequest>>>(emptyMap())

    private val open: StateFlow<ChatState?> = host.session
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val waiting: StateFlow<Map<String, Waiting>> = combine(links, requests, open) { links, requests, open ->
        val openId = open?.storedSessionId
        buildMap {
            requests.values.forEach { (runtimeId, request) ->
                val storedId = links[runtimeId] ?: return@forEach
                if (storedId != openId && storedId !in this) put(storedId, Waiting.of(request))
            }
            if (openId != null) open.inputRequests.firstOrNull()?.let { put(openId, Waiting.of(it)) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            connection.serverRequests.collect { request ->
                val runtimeId = request.sessionId ?: return@collect
                val parsed = InputRequest.parse(request.id, request.method, request.params) ?: return@collect
                requests.update { it + (parsed.id to (runtimeId to parsed)) }
            }
        }
        scope.launch {
            connection.events.collect { event ->
                when (event.type) {
                    "request.cancel", "approval.cancelled" -> {
                        val id = (event.payload as? JsonObject).string("id") ?: (event.payload as? JsonObject).string("request_id")
                        if (id != null) requests.update { it - id }
                    }
                    // Nothing can still wait once the turn is over.
                    "message.complete" -> event.sessionId?.let { runtimeId ->
                        requests.update { all -> all.filterValues { it.first != runtimeId } }
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

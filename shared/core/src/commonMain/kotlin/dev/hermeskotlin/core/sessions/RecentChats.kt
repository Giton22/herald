package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.gateway.GatewayUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The newest chats as the chat list last read them from the gateway, for what shows them outside the app:
 * the iOS Home Screen widget. Null until a list was read, and again after sign-out.
 */
class RecentChats {
    data class Snapshot(val gateway: GatewayUrl, val profile: String?, val sessions: List<SessionSummary>)

    private val _latest = MutableStateFlow<Snapshot?>(null)
    val latest: StateFlow<Snapshot?> = _latest.asStateFlow()

    fun publish(gateway: GatewayUrl, profile: String?, sessions: List<SessionSummary>) {
        _latest.value = Snapshot(gateway, profile, sessions.take(MAX))
    }

    fun clear() {
        _latest.value = null
    }

    private companion object {
        const val MAX = 8
    }
}

package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the one open [ChatSession] for the whole app rather than for a screen, so a running turn keeps
 * streaming (and can be answered from a notification) after the UI goes away, and a recreated UI
 * picks the same session back up instead of starting over.
 */
class ChatHost(
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    private val scope: CoroutineScope,
) {
    private val _session = MutableStateFlow<ChatSession?>(null)
    val session: StateFlow<ChatSession?> = _session.asStateFlow()

    private var gateway: GatewayUrl? = null
    private var sessionScope: CoroutineScope? = null

    /**
     * The session for [storedSessionId] on [gateway]: the open one when it is already that chat, else a
     * fresh one replacing it. A null [storedSessionId] always starts a new chat.
     */
    fun open(gateway: GatewayUrl, storedSessionId: String?, title: String?): ChatSession {
        val current = _session.value
        if (current != null && storedSessionId != null && this.gateway == gateway &&
            current.state.value.storedSessionId == storedSessionId
        ) {
            return current
        }
        close()
        val childScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        this.gateway = gateway
        sessionScope = childScope
        return ChatSession(gateway, storedSessionId, title, connection, sessions, childScope)
            .also { it.start() }
            .also { _session.value = it }
    }

    /** Drops the open chat, e.g. on sign-out. */
    fun close() {
        _session.value?.stop()
        sessionScope?.cancel()
        sessionScope = null
        gateway = null
        _session.value = null
    }
}

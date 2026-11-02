package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.startInstruction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the one open [ChatSession] for the whole app rather than for a screen, so a running turn keeps
 * streaming (and can be answered from a notification) after the UI goes away, and a recreated UI
 * picks the same session back up instead of starting over.
 */
class ChatHost(
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    private val scope: CoroutineScope,
    private val risks: ToolRiskStore? = null,
    private val cache: OfflineCache? = null,
    /** Where the instruction a new chat starts with comes from (see [startInstruction]). */
    private val settings: SettingsStore? = null,
) {
    /** What a new chat started on this device tells the agent first: the setting, read when the chat is created. */
    val startInstruction: () -> String? = { settings?.startInstruction() }

    private val _session = MutableStateFlow<ChatSession?>(null)
    val session: StateFlow<ChatSession?> = _session.asStateFlow()

    private val _sideChats = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Stored ids of chats this device runs outside the open one, with their own [ChatSession] (the watch's).
     * Their owner tells their replies; the watcher and its notifications leave them alone, as for the open chat.
     */
    val sideChats: StateFlow<Set<String>> = _sideChats.asStateFlow()

    fun holdSideChat(storedId: String) = _sideChats.update { it + storedId }

    fun releaseSideChat(storedId: String) = _sideChats.update { it - storedId }

    private val _retitled = MutableSharedFlow<Pair<String, String?>>(extraBufferCapacity = 8)

    /** Chats renamed from the list, as (stored id, new title), for a window of its own showing one. */
    val retitled: SharedFlow<Pair<String, String?>> = _retitled.asSharedFlow()

    /** [storedId] was renamed to [title] from the list: the open chat, if it's that one, shows it, and so does any window. */
    fun retitled(storedId: String, title: String?) {
        _session.value?.takeIf { it.state.value.storedSessionId == storedId }?.showTitle(title)
        _retitled.tryEmit(storedId to title)
    }

    private var gateway: GatewayUrl? = null
    private var profile: String? = null
    private var sessionScope: CoroutineScope? = null

    /** Each closed chat's `/btw` and `/bg` cards, by gateway, profile and stored id, to show again when it reopens. */
    private val sideTasks = mutableMapOf<Triple<GatewayUrl, String?, String>, List<ChatMessage.Command>>()

    /**
     * The session for [storedSessionId] on [gateway] and [profile]: the open one when it is already that
     * chat, else a fresh one replacing it. A null [storedSessionId] always starts a new chat, in [cwd] when
     * given (a project's folder).
     */
    fun open(gateway: GatewayUrl, storedSessionId: String?, title: String?, profile: String? = null, cwd: String? = null): ChatSession {
        val current = _session.value
        if (current != null && storedSessionId != null && this.gateway == gateway && this.profile == profile &&
            current.state.value.storedSessionId == storedSessionId
        ) {
            return current
        }
        stashSideTasks()
        closeOpen()
        val childScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        this.gateway = gateway
        this.profile = profile
        sessionScope = childScope
        val cards = storedSessionId?.let { sideTasks.remove(Triple(gateway, profile, it)) }.orEmpty()
        return ChatSession(gateway, storedSessionId, title, connection, sessions, childScope, profile, risks, cwd = cwd, cache = cache, sideTasks = cards, startInstruction = startInstruction)
            .also { it.start() }
            .also { _session.value = it }
    }

    /** Drops the open chat, e.g. on sign-out, and the side-task cards kept for others: they belonged to that account. */
    fun close() {
        sideTasks.clear()
        closeOpen()
    }

    /** Keeps the open chat's side-task cards for when it reopens; the oldest chat's go past [KEPT_CHATS]. */
    private fun stashSideTasks() {
        val openGateway = gateway ?: return
        val state = _session.value?.state?.value ?: return
        val storedId = state.storedSessionId ?: return
        // A quick answer can beat the task id onto its card and add a second one; keep the answered card.
        val cards = state.messages.filterIsInstance<ChatMessage.Command>().filter { it.taskId != null }
            .groupBy { it.taskId }.values.map { same -> same.firstOrNull { !it.running } ?: same.first() }
        if (cards.isEmpty()) return
        sideTasks[Triple(openGateway, profile, storedId)] = cards
        if (sideTasks.size > KEPT_CHATS) sideTasks.remove(sideTasks.keys.first())
    }

    private fun closeOpen() {
        _session.value?.stop()
        sessionScope?.cancel()
        sessionScope = null
        gateway = null
        profile = null
        _session.value = null
    }

    private companion object {
        const val KEPT_CHATS = 20
    }
}

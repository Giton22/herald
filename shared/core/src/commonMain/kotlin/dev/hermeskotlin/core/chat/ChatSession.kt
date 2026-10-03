package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One chat over the gateway, the way Hermes Desktop drives it: the stored transcript comes over REST,
 * `session.resume {omit_messages}` attaches a live runtime session, and the turn streams in as events.
 * A new chat ([initialStoredId] null) is created with `session.create` on the first send.
 *
 * Re-attaches after every reconnect (runtime ids belong to the socket) and refetches the transcript,
 * since a turn may have finished while we were away.
 */
class ChatSession(
    private val gateway: GatewayUrl,
    initialStoredId: String?,
    initialTitle: String?,
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ChatState(storedSessionId = initialStoredId, title = initialTitle))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** The stored row exists only after the first accepted prompt; until then a reconnect re-creates. */
    private var rowExists = initialStoredId != null
    private val attachMutex = Mutex()
    private var jobs: List<Job> = emptyList()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { if (rowExists) loadHistory() else _state.update { it.copy(historyLoaded = true) } },
            scope.launch { followConnection() },
            scope.launch { followEvents() },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** Sends a prompt, creating or attaching the live session first. Returns false if it was not accepted. */
    suspend fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val key = "local-${_state.value.keySeq}"
        _state.update {
            it.copy(messages = it.messages + ChatMessage.User(key, trimmed, pending = true), keySeq = it.keySeq + 1, error = null)
        }
        return try {
            val client = connectedClient() ?: throw RpcException(0, "Not connected to the gateway. Your message will need resending.")
            val runtimeId = ensureAttached(client)
            val result = client.request(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("text", trimmed)
                },
            ) as? JsonObject
            rowExists = true
            val queued = result.string("status") == "queued"
            _state.update { state ->
                state.copy(
                    running = true,
                    messages = state.messages.updateUser(key) { it.copy(pending = false, queued = queued) },
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { state ->
                state.copy(
                    error = e.message ?: "Couldn't send the message.",
                    // The caller gets the text back to resend; a dead bubble would only duplicate it.
                    messages = state.messages.filterNot { it.key == key },
                )
            }
            false
        }
    }

    /** Asks the gateway to stop the running turn; `message.complete {status: interrupted}` follows. */
    suspend fun interrupt() {
        val client = connectedClient() ?: return
        val runtimeId = _state.value.runtimeSessionId ?: return
        try {
            client.request("session.interrupt", buildJsonObject { put("session_id", runtimeId) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Couldn't stop the turn.") }
        }
    }

    /** Retries the transcript load or a failed attach. */
    fun retry() {
        scope.launch { if (rowExists) loadHistory() }
        val client = connectedClient() ?: return connection.retry()
        if (_state.value.attachment is Attachment.Failed) scope.launch { runCatchingAttach(client) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun followConnection() {
        var attachedBefore = false
        connection.state.collectLatest { connectionState ->
            if (connectionState !is ConnectionState.Connected) {
                _state.update { it.copy(attachment = Attachment.Detached) }
                return@collectLatest
            }
            if (!rowExists) return@collectLatest // a new chat attaches on first send
            if (attachedBefore) loadHistory() // catch up on whatever happened while offline
            if (runCatchingAttach(connectionState.client)) attachedBefore = true
        }
    }

    private suspend fun followEvents() {
        connection.events.collect { event ->
            val runtimeId = _state.value.runtimeSessionId ?: return@collect
            if (event.sessionId == runtimeId) _state.update { it.reduce(event) }
        }
    }

    private suspend fun loadHistory() {
        val id = _state.value.storedSessionId ?: return
        when (val result = sessions.messages(gateway, id)) {
            is ApiResult.Success -> _state.update { state ->
                // Keep a reply that is streaming right now; the stored rows don't have it yet.
                val live = state.messages.filter { it.isLocalOnly }
                state.copy(messages = historyToMessages(result.value.messages) + live, historyLoaded = true, historyError = null)
            }
            else -> _state.update { it.copy(historyLoaded = true, historyError = result.errorMessage) }
        }
    }

    private suspend fun runCatchingAttach(client: JsonRpcClient): Boolean = try {
        attach(client)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(attachment = Attachment.Failed(e.message ?: "Couldn't open the session.")) }
        false
    }

    private suspend fun ensureAttached(client: JsonRpcClient): String {
        _state.value.runtimeSessionId?.let { return it }
        return if (rowExists) attach(client) else create(client)
    }

    /** `session.resume` the stored id; the reply's `session_id` is the runtime id events carry. */
    private suspend fun attach(client: JsonRpcClient): String = attachMutex.withLock {
        val stored = _state.value.storedSessionId ?: error("No stored session to resume")
        _state.update { it.copy(attachment = Attachment.Attaching) }
        val result = client.request(
            "session.resume",
            buildJsonObject {
                put("session_id", stored)
                put("cols", TERMINAL_COLUMNS)
                put("omit_messages", true)
            },
        ) as? JsonObject ?: error("Empty session.resume reply")
        val runtimeId = result.string("session_id") ?: error("session.resume returned no session_id")
        val running = result.boolean("running") == true
        val inflight = result["inflight"] as? JsonObject
        _state.update { state ->
            var messages = state.messages
            val streamed = inflight.string("assistant").orEmpty()
            val hasOpenReply = messages.any { (it as? ChatMessage.Assistant)?.streaming == true }
            if (running && !hasOpenReply) {
                messages = messages + ChatMessage.Assistant(key = "live-${state.keySeq}", text = streamed, streaming = true)
            }
            state.copy(
                attachment = Attachment.Attached(runtimeId),
                running = running,
                messages = messages,
                keySeq = state.keySeq + 1,
                model = (result["info"] as? JsonObject).string("model")?.takeIf { it.isNotBlank() } ?: state.model,
            )
        }
        runtimeId
    }

    /** `session.create` for a brand-new chat; its stored row appears with the first prompt. */
    private suspend fun create(client: JsonRpcClient): String = attachMutex.withLock {
        _state.update { it.copy(attachment = Attachment.Attaching) }
        val result = client.request("session.create", buildJsonObject { put("cols", TERMINAL_COLUMNS) }) as? JsonObject
            ?: error("Empty session.create reply")
        val runtimeId = result.string("session_id") ?: error("session.create returned no session_id")
        _state.update {
            it.copy(
                attachment = Attachment.Attached(runtimeId),
                storedSessionId = result.string("stored_session_id") ?: it.storedSessionId,
                model = (result["info"] as? JsonObject).string("model")?.takeIf { m -> m.isNotBlank() } ?: it.model,
                historyLoaded = true,
            )
        }
        runtimeId
    }

    private fun connectedClient(): JsonRpcClient? = (connection.state.value as? ConnectionState.Connected)?.client

    private companion object {
        /** Width the agent formats terminal-ish output for; a phone is narrow. */
        const val TERMINAL_COLUMNS = 80
    }
}

private fun List<ChatMessage>.updateUser(key: String, change: (ChatMessage.User) -> ChatMessage.User): List<ChatMessage> =
    map { if (it is ChatMessage.User && it.key == key) change(it) else it }

/** Messages the stored transcript can't contain yet: the streaming reply and prompts still being sent. */
private val ChatMessage.isLocalOnly: Boolean
    get() = when (this) {
        is ChatMessage.Assistant -> streaming
        is ChatMessage.User -> pending
    }

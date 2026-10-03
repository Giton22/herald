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

    /**
     * Prompts this client submitted whose turn hasn't started yet. A `message.start` beyond them is a
     * turn another client (Desktop, CLI, a messaging platform) started: the gateway sends no event with
     * that prompt, so the transcript is refetched to show it.
     */
    private var ownTurnsPending = 0
    private var foreignTurn = false
    private var jobs: List<Job> = emptyList()

    /**
     * Requests for a runtime id we don't know yet: one can land between the gateway answering
     * `session.resume`/`session.create` and us reading that reply. Claimed once the id is known.
     */
    private val unclaimed = ArrayDeque<Pair<String, InputRequest>>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { if (rowExists) loadHistory() else _state.update { it.copy(historyLoaded = true) } },
            scope.launch { followConnection() },
            scope.launch { followEvents() },
            scope.launch { followServerRequests() },
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
        // Counted before submitting: the turn's message.start can arrive before the prompt.submit reply.
        ownTurnsPending++
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
            // Steering or redirecting folds the text into the running turn instead of starting one.
            if (result.string("status") !in TURN_STARTING_STATUSES) ownTurnsPending = (ownTurnsPending - 1).coerceAtLeast(0)
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
            ownTurnsPending = (ownTurnsPending - 1).coerceAtLeast(0)
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

    /**
     * Sends [result] as the answer to [request] (see [InputAnswers]). The request leaves the state
     * once the answer is out; another client may have answered first, which the gateway ignores.
     */
    suspend fun answer(request: InputRequest, result: JsonObject): Boolean {
        val client = connectedClient() ?: run {
            _state.update { it.copy(error = "Not connected to the gateway. Answer again once it reconnects.") }
            return false
        }
        return try {
            client.respond(request.id, result)
            _state.update { state -> state.copy(inputRequests = state.inputRequests.filterNot { it.id == request.id }) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Couldn't send the answer.") }
            false
        }
    }

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
            if (event.sessionId != runtimeId) return@collect
            _state.update { it.reduce(event) }
            when (event.type) {
                "message.start" -> if (ownTurnsPending > 0) {
                    ownTurnsPending--
                } else {
                    foreignTurn = true
                    scope.launch { loadHistory() }
                }
                // Swap the live copy for the stored rows, which now hold the other client's prompt for sure.
                "message.complete" -> {
                    // Nothing can still wait on a person once the turn is over; this also clears a
                    // clarify answered on another client, which gets no request.cancel.
                    _state.update { it.copy(inputRequests = emptyList()) }
                    if (foreignTurn) {
                        foreignTurn = false
                        scope.launch { loadHistory() }
                    }
                }
                "request.cancel" -> {
                    val id = (event.payload as? JsonObject).string("id") ?: return@collect
                    _state.update { state -> state.copy(inputRequests = state.inputRequests.filterNot { it.id == id }) }
                }
            }
        }
    }

    private suspend fun followServerRequests() {
        connection.serverRequests.collect { request ->
            val sessionId = request.sessionId ?: return@collect
            val parsed = InputRequest.parse(request.id, request.method, request.params) ?: return@collect
            if (sessionId == _state.value.runtimeSessionId) {
                _state.update { it.copy(inputRequests = it.inputRequests.plusNew(listOf(parsed))) }
            } else {
                unclaimed.addLast(sessionId to parsed)
                if (unclaimed.size > MAX_UNCLAIMED) unclaimed.removeFirst()
            }
        }
    }

    private fun claimUnclaimed(runtimeId: String): List<InputRequest> =
        unclaimed.filter { it.first == runtimeId }.map { it.second }.also { unclaimed.removeAll { it.first == runtimeId } }

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
        // A turn already running when we attach is someone else's; reconcile with the stored rows when it ends.
        if (running && ownTurnsPending == 0) foreignTurn = true
        val inflight = result["inflight"] as? JsonObject
        // Questions asked while no socket of ours was attached; the gateway keeps them open for us.
        val open = result["open_requests"].asObjectList().mapNotNull { snapshot ->
            InputRequest.parse(
                id = snapshot.string("id") ?: return@mapNotNull null,
                method = snapshot.string("method") ?: return@mapNotNull null,
                params = snapshot["params"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
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
                inputRequests = open.plusNew(claimUnclaimed(runtimeId)),
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
                inputRequests = it.inputRequests.plusNew(claimUnclaimed(runtimeId)),
            )
        }
        runtimeId
    }

    private fun connectedClient(): JsonRpcClient? = (connection.state.value as? ConnectionState.Connected)?.client

    private companion object {
        /** Width the agent formats terminal-ish output for; a phone is narrow. */
        const val TERMINAL_COLUMNS = 80

        /** `prompt.submit` statuses that start (or queue) a turn of their own. */
        val TURN_STARTING_STATUSES = setOf("streaming", "queued")

        const val MAX_UNCLAIMED = 8
    }
}

private fun List<InputRequest>.plusNew(more: List<InputRequest>): List<InputRequest> =
    this + more.filter { new -> none { it.id == new.id } }

private fun List<ChatMessage>.updateUser(key: String, change: (ChatMessage.User) -> ChatMessage.User): List<ChatMessage> =
    map { if (it is ChatMessage.User && it.key == key) change(it) else it }

/** Messages the stored transcript can't contain yet: the streaming reply and prompts still being sent. */
private val ChatMessage.isLocalOnly: Boolean
    get() = when (this) {
        is ChatMessage.Assistant -> streaming
        is ChatMessage.User -> pending
    }

package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.slash.SlashCommand
import dev.hermeskotlin.core.slash.SlashResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import dev.hermeskotlin.core.models.ReasoningEffort
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One chat over the gateway, the way Hermes Desktop drives it: the stored transcript comes over REST,
 * `session.resume {omit_messages}` attaches a live runtime session, and the turn streams in as events.
 * A new chat ([initialStoredId] null) is created with `session.create` on the first send.
 *
 * Re-attaches after every reconnect (runtime ids belong to the socket) and refetches the transcript,
 * since a turn may have finished while we were away.
 *
 * [profile] names the Hermes profile the chat belongs to (null: the gateway's launch profile). Only
 * create, resume and REST calls carry it; the gateway scopes the rest by the runtime session.
 */
class ChatSession(
    private val gateway: GatewayUrl,
    initialStoredId: String?,
    initialTitle: String?,
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    private val scope: CoroutineScope,
    private val profile: String? = null,
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

    /**
     * Sends a prompt with any [attachments], creating or attaching the live session first and uploading
     * the attachments to it before `prompt.submit`. Returns false if it was not accepted. The bubble
     * shows [display] instead of the text when given (a skill's body is for the model, not the chat).
     */
    suspend fun send(text: String, attachments: List<OutgoingAttachment> = emptyList(), display: String? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return false
        // Desktop's fallback, so an image-only prompt still asks something.
        val visible = trimmed.ifEmpty { if (attachments.any { it.kind != AttachmentKind.File }) IMAGE_ONLY_PROMPT else "" }
        val key = "local-${_state.value.keySeq}"
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage.User(key, display ?: visible, pending = true, attachments = attachments.map { a -> a.toShown() }),
                keySeq = it.keySeq + 1,
                error = null,
            )
        }
        // Counted before submitting: the turn's message.start can arrive before the prompt.submit reply.
        ownTurnsPending++
        // Images and PDF pages queued on the session: if the prompt never goes out they would ride along
        // with the next one, so a failed send takes them back.
        val queuedImages = mutableListOf<String>()
        var uploadClient: JsonRpcClient? = null
        var uploadRuntimeId: String? = null
        return try {
            val client = connectedClient() ?: throw RpcException(0, "Not connected to the gateway. Your message will need resending.")
            val runtimeId = ensureAttached(client)
            uploadClient = client
            uploadRuntimeId = runtimeId
            val refs = attachments.mapNotNull { upload(client, runtimeId, it, queuedImages) }
            val result = client.request(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", runtimeId)
                    // Like Desktop: the file references first, then what was typed.
                    put("text", (refs + visible).filter { it.isNotEmpty() }.joinToString("\n\n"))
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
            detach(uploadClient, uploadRuntimeId, queuedImages)
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

    /**
     * Uploads one attachment to [runtimeId]. Images and PDF pages are queued for the next turn; a file
     * comes back as the `@file:` reference to put in the prompt, which this returns.
     */
    private suspend fun upload(
        client: JsonRpcClient,
        runtimeId: String,
        attachment: OutgoingAttachment,
        queuedImages: MutableList<String>,
    ): String? {
        val failure = "Couldn't attach ${attachment.name}"
        suspend fun asFile(): String {
            val result = client.request(
                "file.attach",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("name", attachment.name)
                    put("data_url", "data:${attachment.mimeType};base64,${attachment.base64()}")
                },
                timeoutMs = UPLOAD_TIMEOUT_MS,
            ) as? JsonObject
            return result.string("ref_text")?.takeIf { result.boolean("attached") == true } ?: throw RpcException(0, failure)
        }
        return when (attachment.kind) {
            AttachmentKind.Image -> {
                val result = client.request(
                    "image.attach_bytes",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("content_base64", attachment.base64())
                        put("filename", attachment.name)
                    },
                    timeoutMs = UPLOAD_TIMEOUT_MS,
                ) as? JsonObject
                if (result.boolean("attached") != true) throw RpcException(0, result.string("message") ?: failure)
                result.string("path")?.let(queuedImages::add)
                null
            }
            AttachmentKind.Pdf -> try {
                val result = client.request(
                    "pdf.attach",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("content_base64", attachment.base64())
                        put("filename", attachment.name)
                    },
                    timeoutMs = UPLOAD_TIMEOUT_MS,
                ) as? JsonObject
                result?.get("pages").asObjectList().mapNotNullTo(queuedImages) { it.string("path") }
                null
            } catch (e: RpcException) {
                // 5028: the gateway can't render PDFs (no poppler); the agent can still read the file.
                if (e.code == PDF_RENDER_UNAVAILABLE) asFile() else throw e
            }
            AttachmentKind.File -> asFile()
        }
    }

    /** Best effort: un-queues [paths] after a send that failed part-way. */
    private suspend fun detach(client: JsonRpcClient?, runtimeId: String?, paths: List<String>) {
        if (client == null || runtimeId == null) return
        for (path in paths) {
            try {
                client.request("image.detach", buildJsonObject { put("session_id", runtimeId); put("path", path) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return // the socket is likely gone, and the runtime session's queue with it
            }
        }
    }

    /**
     * Runs [command] on the gateway the way Desktop does: `slash.exec` first (built-ins, quick and
     * plugin commands), then `command.dispatch` for what the slash worker won't take (skills, bundles).
     * Output shows under the command in the chat. Returns text to put back in the composer (`/undo`).
     */
    suspend fun runCommand(command: SlashCommand): String? {
        val key = addCommand("/${command.name}")
        val client = connectedClient() ?: return finishCommand(key, NOT_CONNECTED, failed = true).let { null }
        val runtimeId = try {
            ensureAttached(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishCommand(key, e.message ?: "Couldn't open the session.", failed = true)
            return null
        }
        var execError: Exception? = null
        val exec = try {
            val reply = client.request(
                "slash.exec",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("command", command.line)
                },
                timeoutMs = COMMAND_TIMEOUT_MS,
            ) as? JsonObject
            SlashResult.parse(reply) ?: SlashResult.Output("")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            execError = e
            null
        }
        val result = exec ?: try {
            SlashResult.parse(
                client.request(
                    "command.dispatch",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("name", command.name)
                        if (command.arg.isNotEmpty()) put("arg", command.arg)
                    },
                    timeoutMs = COMMAND_TIMEOUT_MS,
                ) as? JsonObject,
            ) ?: SlashResult.Output("The gateway's answer to /${command.name} couldn't be read.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // "Not a quick/plugin/skill command" only means the fallback had nothing either; the worker's
            // failure (a timeout, a crash) is the one worth showing.
            val routingNoise = NOT_DISPATCHABLE.containsMatchIn(e.message.orEmpty())
            val shown = if (routingNoise && execError != null) execError else e
            finishCommand(key, shown.message ?: "/${command.name} failed.", failed = true)
            return null
        }
        return when (result) {
            is SlashResult.Output -> {
                val text = result.text.ifBlank { "Done." }
                finishCommand(key, result.warning?.let { "$it\n\n$text" } ?: text)
                null
            }
            is SlashResult.Alias -> {
                removeMessage(key)
                runCommand(SlashCommand(result.target.lowercase(), command.arg))
            }
            is SlashResult.Send -> {
                result.notice?.takeIf { it.isNotBlank() }?.let { finishCommand(key, it.trim()) } ?: removeMessage(key)
                val display = result.display?.takeIf { it.isNotBlank() } ?: skillInvocationText(result.message) ?: "/${command.line}"
                send(result.message, display = display)
                null
            }
            is SlashResult.Prefill -> {
                result.notice?.takeIf { it.isNotBlank() }?.let { finishCommand(key, it.trim()) } ?: removeMessage(key)
                result.message
            }
        }
    }

    /** `/compress`: summarizes older turns through `session.compress` (the slash worker times out on it). */
    suspend fun compress(focus: String) = runOnGateway("/compress", COMPRESS_TIMEOUT_MS) { client, runtimeId ->
        val result = client.request(
            "session.compress",
            buildJsonObject {
                put("session_id", runtimeId)
                if (focus.isNotBlank()) put("focus_topic", focus.trim())
            },
            timeoutMs = COMPRESS_TIMEOUT_MS,
        ) as? JsonObject
        if (result.boolean("compressed") == false) return@runOnGateway result.string("message") ?: "Nothing to compress yet."
        scope.launch { loadHistory() }
        val before = result.int("before_messages")
        val after = result.int("after_messages")
        val beforeTokens = result.int("before_tokens")
        val afterTokens = result.int("after_tokens")
        buildString {
            append("Compressed")
            if (before != null && after != null) append(": $before → $after messages")
            if (beforeTokens != null && afterTokens != null) append(", about $beforeTokens → $afterTokens tokens")
            append('.')
        }
    }

    /** `/status`: the session report from `session.status`. */
    suspend fun status() = runOnGateway("/status") { client, runtimeId ->
        val result = client.request("session.status", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
        result.string("output") ?: "No status."
    }

    /** `/stop`: stops the reply, then the background processes the agent left running (`process.stop`). */
    suspend fun stopEverything() = runOnGateway("/stop") { client, runtimeId ->
        val lines = mutableListOf<String>()
        if (_state.value.running) {
            client.request("session.interrupt", buildJsonObject { put("session_id", runtimeId) })
            lines += "Stopped the reply."
        }
        val killed = (client.request("process.stop", JsonObject(emptyMap())) as? JsonObject).int("killed") ?: 0
        lines += when (killed) {
            0 -> "No background processes were running."
            1 -> "Stopped 1 background process."
            else -> "Stopped $killed background processes."
        }
        lines.joinToString("\n")
    }

    /**
     * `/btw`: a side question answered from a snapshot of the chat without interrupting it
     * (`prompt.btw`). The answer arrives later as `btw.complete` and fills in the same card.
     */
    suspend fun askAside(question: String) {
        if (question.isBlank()) {
            showCommandOutput("/btw", "Usage: /btw <question>. It's answered from a snapshot of this chat, without interrupting it.")
            return
        }
        val key = addCommand("/btw ${question.trim()}")
        val client = connectedClient() ?: return finishCommand(key, NOT_CONNECTED, failed = true)
        try {
            val runtimeId = ensureAttached(client)
            val result = client.request(
                "prompt.btw",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("text", question.trim())
                },
            ) as? JsonObject
            val taskId = result.string("task_id")
            _state.update { state ->
                state.copy(messages = state.messages.map { if (it is ChatMessage.Command && it.key == key) it.copy(taskId = taskId) else it })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishCommand(key, e.message ?: "Couldn't ask the side question.", failed = true)
        }
    }

    /**
     * `/reasoning [level|show|hide] [--global]` through `config.set reasoning`, as the TUI does; bare,
     * it reports the current setting. Returns the value the gateway settled on.
     */
    suspend fun reasoning(arg: String): String? {
        var settled: String? = null
        runOnGateway("/reasoning") { client, runtimeId ->
            val words = arg.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            val scope = when {
                words.any { it.lowercase() in GLOBAL_FLAGS } -> "global"
                words.any { it.lowercase() in SESSION_FLAGS } -> "session"
                else -> null
            }
            val value = words.filterNot { it.lowercase() in GLOBAL_FLAGS || it.lowercase() in SESSION_FLAGS }.joinToString(" ")
            if (value.isEmpty()) {
                val current = client.request(
                    "config.get",
                    buildJsonObject {
                        put("key", "reasoning")
                        put("session_id", runtimeId)
                    },
                ) as? JsonObject
                return@runOnGateway "Reasoning: ${current.string("value")?.ifBlank { null } ?: "medium"} · " +
                    "thinking ${if (current.string("display") == "show") "shown" else "hidden"}"
            }
            val result = client.request(
                "config.set",
                buildJsonObject {
                    put("key", "reasoning")
                    put("session_id", runtimeId)
                    put("value", value)
                    scope?.let { put("scope", it) }
                },
            ) as? JsonObject
            val applied = result.string("value") ?: value
            settled = applied
            if (ReasoningEffort.fromWire(applied) != null) _state.update { it.copy(reasoningEffort = applied) }
            when (applied) {
                "show" -> "Thinking is shown."
                "hide" -> "Thinking is hidden."
                else -> "Reasoning set to $applied" + if (scope == "global") " for every chat." else "."
            }
        }
        return settled
    }

    /** `/yolo`: skips (or restores) tool approvals for this chat only (`config.set yolo`, session scope). */
    suspend fun toggleYolo() = runOnGateway("/yolo") { client, runtimeId ->
        val next = _state.value.yolo != true
        val result = client.request(
            "config.set",
            buildJsonObject {
                put("key", "yolo")
                put("session_id", runtimeId)
                put("value", if (next) "1" else "0")
            },
        ) as? JsonObject
        val on = result.string("value") == "1"
        _state.update { it.copy(yolo = on) }
        if (on) "YOLO on: this chat runs tools without asking for approval." else "YOLO off: risky tools ask for approval again."
    }

    /** `/title <name>`: renames the chat through `session.title` (REST can't address a live session's row yet). */
    suspend fun retitle(title: String) = runOnGateway("/title") { client, runtimeId ->
        val result = client.request(
            "session.title",
            buildJsonObject {
                put("session_id", runtimeId)
                put("title", title.trim())
            },
        ) as? JsonObject
        val final = (result.string("title") ?: title).trim()
        _state.update { it.copy(title = final.ifEmpty { null }) }
        when {
            final.isEmpty() -> "Title cleared."
            result.boolean("pending") == true -> "Title set: $final (saved once the chat starts)."
            else -> "Title set: $final"
        }
    }

    /**
     * `/branch [count]`: copies this chat (or its first [count] messages) into a new one, as Desktop's
     * branch does. Returns the new chat's stored id and title to open, or null when it failed.
     */
    suspend fun branch(count: Int?): Pair<String, String?>? {
        if (!rowExists) {
            showCommandOutput("/branch", "Nothing to branch yet. Send a message first.", failed = true)
            return null
        }
        var branched: Pair<String, String?>? = null
        runOnGateway("/branch") { client, runtimeId ->
            val params = buildJsonObject {
                put("session_id", runtimeId)
                count?.let { put("count", it) }
            }
            val result = try {
                client.request(if (count == null) "session.branch_whole" else "session.branch", params) as? JsonObject
            } catch (e: RpcException) {
                // Gateways older than branch_whole only have session.branch.
                if (e.code != METHOD_NOT_FOUND) throw e
                client.request("session.branch", params) as? JsonObject
            }
            val stored = result.string("stored_session_id") ?: throw RpcException(0, "The gateway didn't say where the branch went.")
            val title = result.string("title")?.takeIf { it.isNotBlank() }
            branched = stored to title
            "Branched into ${title?.let { "“$it”" } ?: "a new chat"}."
        }
        return branched
    }

    /**
     * `/handoff <platform>`: queues this chat for a messaging platform's home channel and waits for the
     * gateway's messaging service to claim it (`handoff.request`, then `handoff.state`), like Desktop.
     */
    suspend fun handoff(platform: String) {
        val target = platform.trim().lowercase()
        if (target.isEmpty()) {
            showCommandOutput("/handoff", "Usage: /handoff <platform>, e.g. /handoff telegram.")
            return
        }
        runOnGateway("/handoff $target", HANDOFF_WAIT_MS) { client, runtimeId ->
            client.request(
                "handoff.request",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("platform", target)
                },
            )
            val deadline = TimeSource.Monotonic.markNow() + HANDOFF_WAIT_MS.milliseconds
            while (deadline.hasNotPassedNow()) {
                delay(800)
                val record = runCatching {
                    client.request("handoff.state", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
                }.getOrNull() ?: continue
                when (record.string("state")) {
                    "completed" -> return@runOnGateway "Handed off to $target. Continue the chat there."
                    "failed" -> throw RpcException(0, record.string("error")?.ifBlank { null } ?: "The handoff to $target failed.")
                }
            }
            val cleanup = runCatching {
                client.request(
                    "handoff.fail",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("error", "Timed out waiting for the messaging service.")
                    },
                ) as? JsonObject
            }.getOrNull()
            if (cleanup.string("state") == "completed") "Handed off to $target. Continue the chat there."
            else throw RpcException(0, "Nothing picked up the handoff. Is the gateway's messaging service running?")
        }
    }

    /** Shows [text] under [command] at once, for commands this client answers itself. */
    fun showCommandOutput(command: String, text: String, failed: Boolean = false) {
        finishCommand(addCommand(command), text, failed)
    }

    private suspend fun runOnGateway(
        command: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        call: suspend (JsonRpcClient, String) -> String,
    ) {
        val key = addCommand(command)
        val client = connectedClient() ?: return finishCommand(key, NOT_CONNECTED, failed = true)
        try {
            val output = withTimeout(timeoutMs + 5_000) { call(client, ensureAttached(client)) }
            finishCommand(key, output)
        } catch (e: TimeoutCancellationException) {
            finishCommand(key, "$command took too long to answer.", failed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishCommand(key, e.message ?: "$command failed.", failed = true)
        }
    }

    private fun addCommand(command: String): String {
        var key = ""
        _state.update {
            key = "cmd-${it.keySeq}"
            it.copy(messages = it.messages + ChatMessage.Command(key, command), keySeq = it.keySeq + 1, error = null)
        }
        return key
    }

    private fun finishCommand(key: String, output: String, failed: Boolean = false) = _state.update { state ->
        state.copy(
            messages = state.messages.map {
                if (it is ChatMessage.Command && it.key == key) {
                    // The slash worker prints for a terminal; its colour codes mean nothing here.
                    it.copy(output = output.replace(ANSI_ESCAPE, "").trimEnd(), running = false, failed = failed)
                } else {
                    it
                }
            },
        )
    }

    private fun removeMessage(key: String) = _state.update { state -> state.copy(messages = state.messages.filterNot { it.key == key }) }

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

    /**
     * Switches this chat's model. A new chat only remembers the pick for `session.create`; a live one
     * switches at once, or at the next turn when one is running. Pricey models ask to [confirm] first.
     */
    suspend fun setModel(model: String, provider: String, confirm: Boolean = false): ModelSwitch {
        val previous = _state.value
        if (!rowExists && previous.runtimeSessionId == null) {
            _state.update { it.copy(model = model, provider = provider) }
            return ModelSwitch.Done
        }
        _state.update { it.copy(model = model, provider = provider) }
        return try {
            val result = // --session: a pick on the phone must not quietly rewrite the profile default (config.yaml).
            configSet("model", JsonPrimitive("$model --provider $provider --session")) {
                if (confirm) put("confirm_expensive_model", true)
            }
            if (result.boolean("confirm_required") == true) {
                _state.update { it.copy(model = previous.model, provider = previous.provider) }
                ModelSwitch.NeedsConfirmation(result.string("confirm_message") ?: "This model costs more than usual. Switch anyway?")
            } else {
                ModelSwitch.Done
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(model = previous.model, provider = previous.provider, error = e.message ?: "Couldn't switch the model.") }
            ModelSwitch.Failed
        }
    }

    /** Sets the reasoning effort ([effort] `none` turns thinking off) for this chat only. */
    suspend fun setReasoningEffort(effort: String) = setLive({ it.copy(reasoningEffort = effort) }) {
        configSet("reasoning", JsonPrimitive(effort))
    }

    /** Turns the priority tier on or off for this chat only. */
    suspend fun setFast(fast: Boolean) = setLive({ it.copy(fast = fast) }) {
        configSet("fast", JsonPrimitive(if (fast) "fast" else "normal"))
    }

    /** Applies [apply] at once and sends it with [call] when the chat is live; rolls back on failure. */
    private suspend fun setLive(apply: (ChatState) -> ChatState, call: suspend () -> Unit) {
        val previous = _state.value
        _state.update(apply)
        if (!rowExists && previous.runtimeSessionId == null) return
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(reasoningEffort = previous.reasoningEffort, fast = previous.fast, error = e.message ?: "Couldn't change the setting.") }
        }
    }

    /** Session-scoped `config.set`; attaches first, since the key needs a live runtime session. */
    private suspend fun configSet(key: String, value: JsonElement, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject? {
        val client = connectedClient() ?: throw RpcException(0, "Not connected to the gateway.")
        val runtimeId = ensureAttached(client)
        return client.request(
            "config.set",
            buildJsonObject {
                put("session_id", runtimeId)
                put("key", key)
                put("value", value)
                extra()
            },
        ) as? JsonObject
    }

    /** Retries the transcript load or a failed attach. */
    fun retry() {
        scope.launch { if (rowExists) loadHistory() }
        val client = connectedClient() ?: return connection.retry()
        if (_state.value.attachment is Attachment.Failed) scope.launch { runCatchingAttach(client) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Shows a title set elsewhere (a REST rename) without waiting for the gateway to echo it. */
    fun showTitle(title: String?) = _state.update { it.copy(title = title) }

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
        when (val result = sessions.messages(gateway, id, profile = profile)) {
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
                profile?.let { put("profile", it) }
                put("source", CLIENT_SOURCE)
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
            ).withInfo(result["info"] as? JsonObject)
        }
        runtimeId
    }

    /** `session.create` for a brand-new chat; its stored row appears with the first prompt. */
    private suspend fun create(client: JsonRpcClient): String = attachMutex.withLock {
        _state.update { it.copy(attachment = Attachment.Attaching) }
        val picks = _state.value
        val result = client.request(
            "session.create",
            buildJsonObject {
                profile?.let { put("profile", it) }
                put("source", CLIENT_SOURCE)
                put("cols", TERMINAL_COLUMNS)
                // Picked before the first send; without them the profile defaults apply.
                if (picks.model != null && picks.provider != null) {
                    put("model", picks.model)
                    put("provider", picks.provider)
                }
                picks.reasoningEffort?.let { put("reasoning_effort", it) }
                picks.fast?.let { put("fast", it) }
            },
        ) as? JsonObject ?: error("Empty session.create reply")
        val runtimeId = result.string("session_id") ?: error("session.create returned no session_id")
        _state.update {
            it.copy(
                attachment = Attachment.Attached(runtimeId),
                storedSessionId = result.string("stored_session_id") ?: it.storedSessionId,
                historyLoaded = true,
                inputRequests = it.inputRequests.plusNew(claimUnclaimed(runtimeId)),
            ).withInfo(result["info"] as? JsonObject)
        }
        runtimeId
    }

    private fun connectedClient(): JsonRpcClient? = (connection.state.value as? ConnectionState.Connected)?.client

    private companion object {
        /**
         * The surface the agent is told it's on (agent/prompt_builder.py PLATFORM_HINTS). "desktop", like
         * Hermes Desktop and the dashboard chat: Markdown renders and files come back as `MEDIA:` paths,
         * where the default "tui" tells the agent there is no way to show a picture.
         */
        const val CLIENT_SOURCE = "desktop"

        /** Width the agent formats terminal-ish output for; a phone is narrow. */
        const val TERMINAL_COLUMNS = 80

        /** `prompt.submit` statuses that start (or queue) a turn of their own. */
        val TURN_STARTING_STATUSES = setOf("streaming", "queued")

        const val MAX_UNCLAIMED = 8

        /** Uploads of several MB over a phone link take a while. */
        const val UPLOAD_TIMEOUT_MS = 120_000L

        const val PDF_RENDER_UNAVAILABLE = 5028

        const val NOT_CONNECTED = "Not connected to the gateway."

        val ANSI_ESCAPE = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

        /** JSON-RPC "method not found": the gateway predates a call. */
        const val METHOD_NOT_FOUND = -32601

        /** How long a handoff may wait for the messaging service to claim it (Desktop's minute). */
        const val HANDOFF_WAIT_MS = 60_000L

        /** `/reasoning`'s scope words (Desktop's reasoning-slash.ts). */
        val GLOBAL_FLAGS = setOf("--global", "-g", "global")
        val SESSION_FLAGS = setOf("--session", "-s", "session")

        /** The slash worker can be slow to start (it loads the agent and its MCP servers). */
        const val COMMAND_TIMEOUT_MS = 60_000L

        /** Compressing calls the model to summarize; a long chat takes a while. */
        const val COMPRESS_TIMEOUT_MS = 180_000L

        /** `command.dispatch`'s "not a quick/plugin/bundle/skill command" (older gateways lack "bundle/"). */
        val NOT_DISPATCHABLE = Regex("""not a quick/plugin/(?:bundle/)?skill command""", RegexOption.IGNORE_CASE)
    }
}

/** How [ChatSession.setModel] went. */
sealed interface ModelSwitch {
    data object Done : ModelSwitch

    /** The gateway wants an explicit yes (an expensive model); call again with `confirm = true`. */
    data class NeedsConfirmation(val message: String) : ModelSwitch

    /** Rolled back; the reason is in [ChatState.error]. */
    data object Failed : ModelSwitch
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
        is ChatMessage.Command -> true
    }

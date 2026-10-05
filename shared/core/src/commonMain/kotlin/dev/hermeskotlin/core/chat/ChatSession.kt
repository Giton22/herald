package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.rpc.RpcTimeoutException
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.slash.SlashCommand
import dev.hermeskotlin.core.slash.SlashResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import dev.hermeskotlin.core.models.ReasoningEffort
import kotlin.time.Clock
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One chat over the gateway, the way Hermes Desktop drives it: the stored transcript comes over REST,
 * `session.resume {omit_messages}` attaches a live runtime session, and the turn streams in as events.
 * A new chat ([initialStoredId] null) is created with `session.create` on the first send.
 *
 * Re-attaches after every reconnect (runtime ids belong to the socket). What happened while we were away is
 * replayed from the gateway's event ring (`session.events.since`) when it still holds it; otherwise the
 * transcript is refetched, since a turn may have finished meanwhile.
 *
 * The transcript loads a page at a time: the newest on opening, older ones through [loadOlder].
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
    /** The profile the chat runs in; null for the gateway's launch profile. */
    val profile: String? = null,
    /** Where flagged tool output is remembered, since the transcript forgets it. */
    private val risks: ToolRiskStore? = null,
    /** Dates what this device sends and sees finish; live events carry no time of their own. */
    private val clock: Clock = Clock.System,
    /** For a new chat: the working folder it starts in (a project's), or null for the profile's default. */
    private val cwd: String? = null,
) {
    private fun nowSeconds(): Double = clock.now().toEpochMilliseconds() / 1000.0

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

    /** A [rewind] cut the transcript; the stored rows replace the live copy once its turn ends. */
    private var rewound = false
    private var jobs: List<Job> = emptyList()

    /** What went out for each bubble marked by a [SendCheck], by key: a skill's bubble shows less than was sent. */
    private val unsettled = mutableMapOf<String, UnsettledPrompt>()

    /**
     * Requests for a runtime id we don't know yet: one can land between the gateway answering
     * `session.resume`/`session.create` and us reading that reply. Claimed once the id is known.
     */
    private val unclaimed = ArrayDeque<Pair<String, InputRequest>>()

    /** The stored rows loaded so far, oldest first: the newest page and any older ones scrolled back to. */
    private var rows: List<SessionMessage> = emptyList()

    /** The session the newest page was read from; prompts stored under an older one can't be cut at. */
    private var rowsSessionId: String? = null

    /** Rows an older page found already loaded, as the transcript grew since: the next one reads past them. */
    private var olderSkew = 0
    private val historyMutex = Mutex()

    /**
     * Where the event stream stands, to pick it up after a drop: the seq of the last event applied, the runtime
     * session it counts in and the gateway run (`replay_epoch`) numbering it. Null seq: nothing to resume from.
     */
    private var lastSeq: Long? = null
    private var seqRuntime: String? = null
    private var seqEpoch: String? = null

    /** Live events held back while the gap before them is filled; null when nothing is being caught up. */
    private var held: MutableList<GatewayEvent>? = null

    /** Orders applying events, holding them and filling gaps, which run on different threads. Never held across I/O. */
    private val streamMutex = Mutex()

    /** The running turn's reply so far as the last `session.resume` had it, for when the gap can't be filled. */
    private var inflightText: String? = null

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
     *
     * Mid-turn, the gateway folds text into the running turn (Desktop's stop-and-correct) unless [queue]
     * asks for it to run as the next turn instead.
     *
     * A prompt that went out without a reply may be running, so the stored transcript decides before
     * this returns (see [settleUnanswered]).
     */
    suspend fun send(
        text: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        display: String? = null,
        queue: Boolean = false,
    ): Boolean = submit(text, attachments, display, queue) == SendOutcome.Sent

    /** [send], telling a prompt that's gone apart from one whose bubble stays [SendOutcome.Unsettled]. */
    suspend fun submit(
        text: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        display: String? = null,
        queue: Boolean = false,
    ): SendOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return SendOutcome.NotSent
        // Desktop's fallback, so an image-only prompt still asks something.
        val visible = trimmed.ifEmpty { if (attachments.any { it.kind != AttachmentKind.File }) IMAGE_ONLY_PROMPT else "" }
        val key = "local-${_state.value.keySeq}"
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage.User(
                    key,
                    display ?: visible,
                    pending = true,
                    attachments = attachments.map { a -> a.toShown() },
                    timestamp = nowSeconds(),
                ),
                keySeq = it.keySeq + 1,
                error = null,
                refused = null,
            )
        }
        // Counted before submitting: the turn's message.start can arrive before the prompt.submit reply.
        ownTurnsPending++
        // Images and PDF pages queued on the session: if the prompt never goes out they would ride along
        // with the next one, so a failed send takes them back.
        val queuedImages = mutableListOf<String>()
        var uploadClient: JsonRpcClient? = null
        var uploadRuntimeId: String? = null
        // Only a prompt that starts a turn is written to the transcript at once; a correction or a
        // queued one isn't, so for those the transcript can't say whether it arrived.
        val startsTurn = !queue && !_state.value.running
        // The prompts shown, which the transcript has too; not countable when the transcript failed to load.
        val promptsBefore = _state.value.takeIf { it.historyError == null }
            ?.messages?.count { it is ChatMessage.User && !it.pending && it.check == null }
        var submitted = false
        return try {
            val client = connectedClient() ?: throw RpcException(0, "Not connected to the gateway. Your message will need resending.")
            val runtimeId = ensureAttached(client)
            uploadClient = client
            uploadRuntimeId = runtimeId
            val refs = attachments.mapNotNull { upload(client, runtimeId, it, queuedImages) }
            submitted = true
            val result = client.request(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", runtimeId)
                    // Like Desktop: the file references first, then what was typed.
                    put("text", (refs + visible).filter { it.isNotEmpty() }.joinToString("\n\n"))
                    if (queue) put("queued", true)
                },
            ) as? JsonObject
            rowExists = true
            val status = result.string("status")
            // Steering or redirecting folds the text into the running turn instead of starting one.
            if (status !in TURN_STARTING_STATUSES) ownTurnsPending = (ownTurnsPending - 1).coerceAtLeast(0)
            // The row written for it, so it can be regenerated or edited before the transcript is read again.
            val rowId = result.long("user_row_id")
            val sentText = visible.takeIf { attachments.isEmpty() && display == null && it.isNotEmpty() }
            _state.update { state ->
                val sent = state.copy(
                    running = true,
                    messages = state.messages.updateUser(key) {
                        it.copy(pending = false, queued = status == "queued", rowId = rowId, sentText = sentText)
                    },
                )
                // What streamed before the correction stays above it; the rest of the turn continues below.
                if (status in CORRECTION_STATUSES) sent.sealReplyBefore(key) else sent
            }
            SendOutcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ownTurnsPending = (ownTurnsPending - 1).coerceAtLeast(0)
            // An error reply is the gateway turning it down; anything else after it went out is no verdict.
            if (submitted && e !is RpcException) {
                val arrived = if (startsTurn) findInTranscript(visible, key, promptsBefore) else null
                return settleUnanswered(key, arrived, UnsettledPrompt(text, display, startsTurn)) {
                    detach(uploadClient, uploadRuntimeId, queuedImages)
                }
            }
            detach(uploadClient, uploadRuntimeId, queuedImages)
            val refused = SessionRefusal.of(e)
            _state.update { state ->
                state.copy(
                    error = if (refused != null) null else e.message ?: "Couldn't send the message.",
                    refused = refused,
                    // The caller gets the text back to resend; a dead bubble would only duplicate it.
                    messages = state.messages.filterNot { it.key == key },
                )
            }
            SendOutcome.NotSent
        }
    }

    /**
     * Settles a prompt that went out without a reply, once the transcript had its say ([arrived]). In it:
     * sent, and the stored rows take over. Missing from it: the caller gets the text back as for any
     * failure. Can't tell: the bubble stays, marked, so a resend is made knowing it may already be running.
     */
    private suspend fun settleUnanswered(
        key: String,
        arrived: Boolean?,
        prompt: UnsettledPrompt,
        takeBack: suspend () -> Unit,
    ): SendOutcome {
        when (arrived) {
            true -> {
                rowExists = true
                _state.update { it.copy(messages = it.messages.updateUser(key) { u -> u.copy(check = null) }) }
                loadHistory()
            }
            false -> {
                takeBack()
                _state.update { state ->
                    state.copy(
                        error = "Hermes didn't get your message. It's back in the composer.",
                        messages = state.messages.filterNot { it.key == key },
                    )
                }
            }
            null -> {
                unsettled[key] = prompt
                _state.update { state ->
                    state.copy(
                        error = "Lost the connection while sending. Check that it isn't running before you send it again.",
                        messages = state.messages.updateUser(key) { it.copy(pending = false, check = SendCheck.Unknown) },
                    )
                }
            }
        }
        return when (arrived) {
            true -> SendOutcome.Sent
            false -> SendOutcome.NotSent
            null -> SendOutcome.Unsettled
        }
    }

    /**
     * Looks for the unsettled prompt [key] in the stored transcript again, when the user asks. Found: it
     * counts as sent. Missing: marked [SendCheck.NotReceived], safe to resend. Still can't tell: it stays
     * [SendCheck.Unknown]. Never sends anything itself.
     */
    suspend fun checkDelivery(key: String) {
        val bubble = _state.value.messages.firstOrNull { it.key == key } as? ChatMessage.User ?: return
        if (bubble.check == null || bubble.check == SendCheck.Checking) return
        // Reloaded first only when it never loaded, since the count below needs it: a reload settles any
        // prompt whose text the transcript has anywhere, an earlier one alike, so it can't be the check.
        if (_state.value.historyError != null) loadHistory()
        val messages = _state.value.messages
        val index = messages.indexOfFirst { it.key == key }
        if (index < 0) return run { unsettled.remove(key) }
        val before = _state.value.takeIf { it.historyError == null }?.let {
            messages.take(index).count { m -> m is ChatMessage.User && !m.pending && m.check == null }
        }
        val arrived = findInTranscript(bubble.text, key, before)
        // A correction or a queued prompt is written only once the turn takes it in, so mid-turn a miss proves nothing.
        val mayBeWaiting = arrived == false && unsettled[key]?.startsTurn == false && _state.value.running
        when {
            arrived == true -> {
                rowExists = true
                unsettled.remove(key)
                _state.update { it.copy(error = null, messages = it.messages.updateUser(key) { u -> u.copy(check = null) }) }
                loadHistory()
            }
            arrived == false && !mayBeWaiting ->
                _state.update { it.copy(error = null, messages = it.messages.updateUser(key) { u -> u.copy(check = SendCheck.NotReceived) }) }
            else -> _state.update { state ->
                state.copy(
                    error = if (mayBeWaiting) {
                        "Hermes may still be holding it for the running turn. Check again once the turn ends."
                    } else {
                        "Couldn't read the conversation to check. Try again once connected."
                    },
                    messages = state.messages.updateUser(key) { it.copy(check = SendCheck.Unknown) },
                )
            }
        }
    }

    /**
     * Removes the unsettled prompt [key] (one marked by a [SendCheck]) and hands back its text as shown, to
     * edit; null for any other message.
     */
    fun takeBack(key: String): String? {
        val message = _state.value.messages.firstOrNull { it.key == key } as? ChatMessage.User ?: return null
        if (message.check == null || message.check == SendCheck.Checking) return null
        unsettled.remove(key)
        _state.update { state -> state.copy(messages = state.messages.filterNot { it.key == key }) }
        return message.text
    }

    /**
     * Sends the unsettled prompt [key] again as it first went out (a skill's full body, not the command its
     * bubble shows), in place of its bubble. Returns the text shown to give back to the composer when it
     * didn't go and no bubble kept it; null otherwise.
     */
    suspend fun resend(key: String): String? {
        val prompt = unsettled[key]
        val shown = takeBack(key) ?: return null
        val outcome = submit(prompt?.text ?: shown, display = prompt?.display)
        return shown.takeIf { outcome == SendOutcome.NotSent }
    }

    /**
     * Rewinds the chat to prompt [key] and sends [text] in its place: a regenerate sends the prompt's own text
     * again, an edit a new one. The prompt and everything after it go, here and on the gateway, in one
     * `prompt.submit` that cuts the stored transcript before the prompt's row, as Desktop's rewind does. Only
     * between turns, and only for a prompt with a stored row ([ChatMessage.User.rowId]).
     *
     * Refused (busy, or the gateway can't find the row any more): nothing changed, and [SendOutcome.NotSent].
     * When the link drops before the answer, the stored transcript says whether it went.
     */
    suspend fun rewind(key: String, text: String): SendOutcome {
        val trimmed = text.trim()
        val before = _state.value
        val index = before.messages.indexOfFirst { it.key == key }
        val rowId = (before.messages.getOrNull(index) as? ChatMessage.User)?.rowId
        if (trimmed.isEmpty() || rowId == null) return SendOutcome.NotSent
        // The gateway won't cut the chat mid-turn; say so rather than leave the send doing nothing.
        if (before.running) {
            _state.update { it.copy(error = BUSY_MESSAGE) }
            return SendOutcome.NotSent
        }
        val client = connectedClient() ?: run {
            _state.update { it.copy(error = "$NOT_CONNECTED Try again once it reconnects.") }
            return SendOutcome.NotSent
        }
        val newKey = "local-${before.keySeq}"
        // Shown cut at once; put back as it was if the gateway turns it down.
        _state.update {
            it.copy(
                messages = it.messages.take(index) + ChatMessage.User(newKey, trimmed, pending = true),
                keySeq = it.keySeq + 1,
                error = null,
                refused = null,
            )
        }
        ownTurnsPending++
        var submitted = false
        return try {
            val runtimeId = ensureAttached(client)
            submitted = true
            val result = client.request(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("text", trimmed)
                    put("truncate_before_row_id", rowId)
                    put("confirm_truncate", true)
                    // Cutting at the first prompt empties the transcript, which the gateway wants said outright.
                    put("confirm_empty_truncate", true)
                },
            ) as? JsonObject
            if (result.string("status") !in TURN_STARTING_STATUSES) ownTurnsPending = (ownTurnsPending - 1).coerceAtLeast(0)
            // The stored rows decide once the turn is over, so what shows matches the gateway's transcript.
            rewound = true
            _state.update { state ->
                state.copy(
                    running = true,
                    messages = state.messages.updateUser(newKey) {
                        it.copy(pending = false, rowId = result.long("user_row_id"), sentText = trimmed)
                    },
                )
            }
            SendOutcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ownTurnsPending = (ownTurnsPending - 1).coerceAtLeast(0)
            if (submitted && e !is RpcException) {
                // No answer: the cut may have happened. The transcript shows which, and its last prompt says whether this went.
                _state.update { state -> state.copy(messages = state.messages.filterNot { it.key == newKey }) }
                loadHistory()
                val arrived = _state.value.historyError == null &&
                    (_state.value.messages.lastOrNull { it is ChatMessage.User } as? ChatMessage.User)?.text == trimmed
                if (arrived) {
                    rewound = true
                    return SendOutcome.Sent
                }
                _state.update { it.copy(error = "Lost the connection while sending. The chat shows what Hermes has.") }
                return SendOutcome.NotSent
            }
            val stale = (e as? RpcException)?.code in STALE_REWIND_CODES
            val refused = SessionRefusal.of(e)
            _state.update { state ->
                state.copy(
                    messages = before.messages,
                    refused = refused,
                    error = when {
                        refused != null -> null
                        (e as? RpcException)?.code == BUSY -> BUSY_MESSAGE
                        stale -> "This message can't be changed any more."
                        else -> e.message ?: "Couldn't send the message."
                    },
                )
            }
            // The gateway's transcript isn't what this chat shows; show it as it is.
            if (stale) loadHistory()
            SendOutcome.NotSent
        }
    }

    /**
     * Whether the transcript has a prompt more than the [before] shown ahead of the send, the latest being
     * [visible], while bubble [key] says it's being checked. Counted, not matched by key: an own prompt's
     * bubble keeps its local key, so its stored row always looks new. The gateway writes the prompt as the
     * turn starts, so a few looks a moment apart cover a slow start. Null when the transcript couldn't be read.
     */
    private suspend fun findInTranscript(visible: String, key: String, before: Int?): Boolean? {
        _state.update { it.copy(messages = it.messages.updateUser(key) { u -> u.copy(pending = false, check = SendCheck.Checking) }) }
        before ?: return null
        val id = _state.value.storedSessionId ?: return null
        var read = false
        repeat(DELIVERY_LOOKS) { look ->
            if (look > 0) delay(DELIVERY_LOOK_INTERVAL_MS)
            when (val result = sessions.messages(gateway, id, limit = HISTORY_PAGE, profile = profile)) {
                is ApiResult.Success -> {
                    // Counted over the same stretch as the prompts shown: the loaded rows, brought up to date.
                    val stored = rows.afterNewestPage(result.value.messages) ?: return@repeat
                    read = true
                    val prompts = historyToMessages(stored).filterIsInstance<ChatMessage.User>()
                    // Any prompt past the ones shown before it: later prompts may follow it by a later check.
                    if (prompts.drop(before).any { it.text.contains(visible) }) return true
                }
                // A new chat's stored row only appears with its first prompt.
                is ApiResult.Failed -> if (result.status == 404 && !rowExists) read = true
                else -> {}
            }
        }
        return if (read) false else null
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
                // `/undo` took turns off the stored transcript; show it without them.
                if (rowExists) loadHistory()
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

    /**
     * The provider account's limits and credits as the gateway words them (`session.usage`
     * `account_lines` / `credits_lines`, e.g. a quota window); empty when there are none or offline.
     */
    suspend fun accountLimits(): List<String> {
        val client = connectedClient() ?: return emptyList()
        val runtimeId = _state.value.runtimeSessionId ?: return emptyList()
        return try {
            val result = client.request("session.usage", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
            listOf("account_lines", "credits_lines").flatMap { key ->
                (result?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** What fills the context window now (`session.context_breakdown`); null before the first prompt or offline. */
    suspend fun contextBreakdown(): ContextBreakdown? {
        val client = connectedClient() ?: return null
        val runtimeId = _state.value.runtimeSessionId ?: return null
        return try {
            ContextBreakdown.parse(
                client.request("session.context_breakdown", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** The background processes this chat's agent started (`process.list`); null when offline or before the first prompt. */
    suspend fun processes(): List<BackgroundProcess>? {
        val client = connectedClient() ?: return null
        val runtimeId = _state.value.runtimeSessionId ?: return null
        return try {
            BackgroundProcess.parseList(client.request("process.list", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Stops one background process (`process.kill`), and says how that went. */
    suspend fun killProcess(processId: String): String {
        val client = connectedClient() ?: return NOT_CONNECTED
        val runtimeId = _state.value.runtimeSessionId ?: return NOT_CONNECTED
        return try {
            killOutcome(
                client.request(
                    "process.kill",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("process_id", processId)
                    },
                ) as? JsonObject,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Couldn't stop it."
        }
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
        } catch (e: RpcTimeoutException) {
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

    /**
     * Slips [text] into the running turn with `session.steer`: the agent reads it after its current tool
     * call, without stopping. With nothing running, or when the gateway turns it down (`rejected`), it goes
     * as the next prompt instead; a gateway that can't steer (no such method, or an agent without it) gets a
     * plain prompt, which its busy mode folds in as before.
     *
     * A steer the turn ended before reading isn't lost: the gateway queues the leftover as the next prompt,
     * and that turn shows up like one started elsewhere, its prompt pulled from the transcript.
     */
    suspend fun steer(text: String): SendOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SendOutcome.NotSent
        val client = connectedClient()
        val runtimeId = _state.value.runtimeSessionId
        // Offline, submit says so and hands the text back.
        if (!_state.value.running || client == null || runtimeId == null) return submit(trimmed, queue = true)
        val key = "local-${_state.value.keySeq}"
        _state.update {
            it.copy(messages = it.messages + ChatMessage.User(key, trimmed, pending = true), keySeq = it.keySeq + 1, error = null)
        }
        val status = try {
            (client.request("session.steer", buildJsonObject {
                put("session_id", runtimeId)
                put("text", trimmed)
            }) as? JsonObject).string("status")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            removeMessage(key)
            if (e.code in STEER_UNSUPPORTED) return submit(trimmed)
            _state.update { it.copy(error = e.message.ifBlank { "Couldn't steer the reply." }) }
            return SendOutcome.NotSent
        } catch (e: Exception) {
            // Out without a reply: the agent may have read it, so it isn't handed back to go twice. Like a
            // lost correction, the bubble stays marked; a steer leaves nothing in the transcript to settle it.
            return settleUnanswered(key, arrived = null, UnsettledPrompt(trimmed, display = null, startsTurn = false)) {}
        }
        if (status != STEER_ACCEPTED) {
            // Turned down: the turn ended meanwhile, or the agent can't take it now. It runs next instead.
            removeMessage(key)
            return submit(trimmed, queue = true)
        }
        _state.update { state ->
            // Like a correction: what streamed so far stays above it, the rest of the turn continues below.
            state.copy(messages = state.messages.updateUser(key) { it.copy(pending = false) }).sealReplyBefore(key)
        }
        return SendOutcome.Sent
    }

    /**
     * Stops the running reply (`session.interrupt`), waits for the turn to settle, then sends [text] as a
     * fresh turn. The gateway drops the prompts queued behind the stopped turn; their text comes back in
     * [StopAndSend.dropped]. A turn that won't settle in time gets the prompt queued behind it, so it still
     * runs after it rather than folding into the turn it was meant to replace.
     */
    suspend fun stopAndSubmit(
        text: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        display: String? = null,
    ): StopAndSend {
        if (!_state.value.running) return StopAndSend(submit(text, attachments, display))
        val dropped = interruptTurn() ?: run {
            _state.update { it.copy(error = it.error ?: NOT_CONNECTED) }
            return StopAndSend(SendOutcome.NotSent)
        }
        val settled = withTimeoutOrNull(STOP_SETTLE_TIMEOUT_MS) { _state.first { !it.running } } != null
        return StopAndSend(submit(text, attachments, display, queue = !settled), dropped)
    }

    /**
     * Asks the gateway to stop the running turn; `message.complete {status: interrupted}` follows. The
     * gateway drops the prompts queued behind the turn too, so their bubbles go and their text comes back,
     * in order, for the composer.
     */
    suspend fun interrupt(): List<String> = interruptTurn().orEmpty()

    /** [interrupt], null when the turn couldn't be stopped (the reason is in [ChatState.error]). */
    private suspend fun interruptTurn(): List<String>? {
        val client = connectedClient() ?: return null
        val runtimeId = _state.value.runtimeSessionId ?: return null
        try {
            client.request("session.interrupt", buildJsonObject { put("session_id", runtimeId) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Couldn't stop the turn.") }
            // Turned down by the gateway, the runtime may be gone (its bot deleted mid-turn, say): asked again,
            // a chat that still exists shows its turn as it is, and one that doesn't stops showing one.
            if (e is RpcException) runCatchingAttach(client, reconnected = true)
            return null
        }
        var dropped = emptyList<String>()
        _state.update { state ->
            val (queued, kept) = state.messages.partition { it is ChatMessage.User && it.queued }
            dropped = queued.map { (it as ChatMessage.User).text }
            state.copy(messages = kept)
        }
        return dropped
    }

    /** Stops one running subagent (`subagent.interrupt`); its `subagent.complete` follows. */
    suspend fun stopSubagent(subagentId: String) {
        val client = connectedClient() ?: return
        val runtimeId = _state.value.runtimeSessionId ?: return
        try {
            val result = client.request(
                "subagent.interrupt",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("subagent_id", subagentId)
                },
            ) as? JsonObject
            // Already over: nothing will say so, so stop showing it as running.
            if (result.boolean("found") == false) {
                _state.update { state ->
                    state.copy(subagents = state.subagents.map {
                        if (it.id == subagentId && it.status.live) it.copy(status = SubagentStatus.Interrupted) else it
                    })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Couldn't stop the subagent.") }
        }
    }

    /** Subagents already running when this chat attached (`subagent.list`); their events only cover what comes next. */
    private suspend fun refreshSubagents(client: JsonRpcClient, runtimeId: String) {
        val result = try {
            client.request("subagent.list", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // an older gateway without the method; live events still show new ones
        } ?: return
        val snapshots = result["subagents"].asObjectList()
        if (snapshots.isNotEmpty()) _state.update { it.withSubagentSnapshots(snapshots) }
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

    fun dismissError() = _state.update { it.copy(error = null, refused = null) }

    /** Shows a title set elsewhere (a REST rename) without waiting for the gateway to echo it. */
    fun showTitle(title: String?) = _state.update { it.copy(title = title) }

    /**
     * Shows a model set elsewhere (a Bot Chat's bot's own, which the chat follows) without asking the
     * gateway to pin it here; [error] says it couldn't be set.
     */
    fun showModel(model: String?, provider: String?, error: String? = null) =
        _state.update { if (error != null) it.copy(error = error) else it.copy(model = model, provider = provider) }

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
            // Load what couldn't be read before the link came up; after a drop, attaching catches up.
            if (!attachedBefore && _state.value.historyError != null) loadHistory()
            // A chat created on this link was live too, though it never resumed: it catches up the same way.
            val reconnected = attachedBefore || streamMutex.withLock { seqRuntime != null }
            if (runCatchingAttach(connectionState.client, reconnected = reconnected)) attachedBefore = true
        }
    }

    private suspend fun followEvents() {
        connection.events.collect { event ->
            val runtimeId = _state.value.runtimeSessionId ?: return@collect
            if (event.sessionId != runtimeId) return@collect
            streamMutex.withLock { deliver(event) }
        }
    }

    /**
     * Applies a live event in order: one already applied (replayed, or sent twice) is skipped, and one past a gap
     * waits, with those after it, while [catchUp] fills the gap. Called under [streamMutex].
     */
    private fun deliver(event: GatewayEvent) {
        held?.let {
            it += event
            return
        }
        val seq = event.seq
        val last = lastSeq
        if (seq != null && last != null) {
            if (seq <= last) return
            if (seq > last + 1) {
                held = mutableListOf(event)
                val client = connectedClient()
                val runtimeId = _state.value.runtimeSessionId
                if (client != null && runtimeId != null) scope.launch { catchUp(client, runtimeId) } else held = null
                return
            }
        }
        apply(event)
    }

    /**
     * Fills the gap after [lastSeq] from the gateway's event ring (`session.events.since`), then lets the held live
     * events through. When the ring can't fill it (an older gateway, too long away, a restarted gateway) the chat
     * reloads the transcript and takes the running reply as the gateway has it, as it did before replay existed.
     */
    private suspend fun catchUp(client: JsonRpcClient, runtimeId: String) {
        val from = streamMutex.withLock { lastSeq }
        val missed = if (from == null) null else try {
            val reply = client.request(
                "session.events.since",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("last_seen", from)
                },
            ) as? JsonObject
            EventReplay.parse(reply, runtimeId, from, seqEpoch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // method missing on an older gateway, or the link went again
        }
        // Read while live events stay held; the stream lock is only taken to apply.
        if (missed == null && rowExists && _state.value.runtimeSessionId == runtimeId) loadHistory()
        streamMutex.withLock {
            // The link went, or another runtime took over: the next attach starts its own catch-up.
            if (_state.value.runtimeSessionId != runtimeId) {
                held = null
                return
            }
            if (missed != null) {
                missed.forEach(::apply)
            } else {
                lastSeq = null
                val streamed = inflightText
                if (streamed != null && _state.value.running) {
                    _state.update { it.withInflight(streamed) }
                } else if (!_state.value.running) {
                    _state.update { it.withoutStaleReply() }
                }
            }
            val waiting = held.orEmpty()
            held = null
            waiting.forEach(::deliver)
        }
    }

    private fun apply(event: GatewayEvent) {
        event.seq?.let { lastSeq = it }
        val now = nowSeconds()
        _state.update { it.reduce(event, now) }
        when (event.type) {
            "tool.output_risk" -> flaggedOutput(event.payload as? JsonObject)?.let { (id, risk) ->
                risks?.let { scope.launch { it.remember(id, risk) } }
            }
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
                if (foreignTurn || rewound) {
                    foreignTurn = false
                    rewound = false
                    scope.launch { loadHistory() }
                }
            }
            "request.cancel" -> {
                val id = (event.payload as? JsonObject).string("id") ?: return
                _state.update { state -> state.copy(inputRequests = state.inputRequests.filterNot { it.id == id }) }
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

    /**
     * Reads the newest page of the transcript and lays it over the loaded rows, so older pages scrolled back to
     * stay. When it no longer meets them (many turns since), the chat starts over from the newest page.
     */
    private suspend fun loadHistory() = historyMutex.withLock {
        val id = _state.value.storedSessionId ?: return
        val result = sessions.messages(gateway, id, limit = HISTORY_PAGE, profile = profile)
        val flagged = risks?.all().orEmpty()
        when (result) {
            is ApiResult.Success -> {
                val page = result.value.messages
                val full = page.size >= HISTORY_PAGE
                val joined = if (full && rows.isNotEmpty()) rows.withNewest(page.fromFirstTurn()) else null
                val older = if (joined != null) _state.value.olderMessages else full
                rows = joined ?: if (full) page.fromFirstTurn() else page
                rowsSessionId = result.value.sessionId
                olderSkew = 0
                showRows(flagged, older)
            }
            else -> _state.update {
                it.copy(
                    historyLoaded = true,
                    historyError = result.errorMessage,
                    // Gone from the gateway (deleted elsewhere, or with its bot): nothing of it is still running.
                    running = it.running && !(result is ApiResult.Failed && result.status == HTTP_NOT_FOUND),
                )
            }
        }
    }

    /**
     * Loads the page of the transcript before the loaded rows, when there is one, as the reader nears the top.
     * Its messages go above the ones shown, which keep their keys, so the list stays where it was.
     */
    suspend fun loadOlder() {
        if (_state.value.loadingOlder) return
        loadOlderPage(HISTORY_PAGE)
    }

    /**
     * Loads every older page, for what needs the whole transcript (counting rows to branch at). False when a page
     * couldn't be read.
     */
    suspend fun loadAllHistory(): Boolean {
        repeat(MAX_HISTORY_PAGES) {
            if (!_state.value.olderMessages) return true
            if (!loadOlderPage(HISTORY_PAGE_MAX)) return false
        }
        return !_state.value.olderMessages
    }

    private suspend fun loadOlderPage(limit: Int): Boolean {
        val id = _state.value.storedSessionId ?: return false
        if (!_state.value.olderMessages) return true
        _state.update { it.copy(loadingOlder = true) }
        try {
            return historyMutex.withLock {
                // Counted back from the newest row, so it reads from just before the oldest one loaded.
                val result = sessions.messages(gateway, id, limit = limit, profile = profile, offset = rows.size + olderSkew)
                if (result !is ApiResult.Success) {
                    _state.update { it.copy(error = "Couldn't load earlier messages. ${result.errorMessage}".trim()) }
                    return@withLock false
                }
                val page = result.value.messages
                val full = page.size >= limit
                val known = rows.mapNotNullTo(HashSet()) { it.id }
                // Rows already loaded come again when the transcript grew since: skip them, and past them next time.
                val fresh = (if (full) page.fromFirstTurn() else page).filter { it.id == null || it.id !in known }
                // Past only those: rows before the page's first prompt that aren't loaded yet come with the next page.
                if (fresh.isEmpty() && full) olderSkew += page.count { it.id != null && it.id in known }
                val shownBefore = historyToMessages(rows, rowsSessionId).mapTo(HashSet()) { it.key }
                rows = fresh + rows
                val flagged = risks?.all().orEmpty()
                _state.update { state ->
                    // The stored rows lead the list; what follows them (the live turn, local notes) stays as it is.
                    val stored = state.messages.takeWhile { it.key in shownBefore }.size
                    state.copy(
                        messages = historyToMessages(rows, rowsSessionId).withRisks(flagged) + state.messages.drop(stored),
                        olderMessages = full,
                    )
                }
                true
            }
        } finally {
            _state.update { it.copy(loadingOlder = false) }
        }
    }

    /** Shows the loaded [rows] in place of the stored messages, keeping what they can't hold yet. */
    private fun showRows(flagged: Map<String, ToolRisk>, older: Boolean) = _state.update { state ->
        // Keep a reply that is streaming right now; the stored rows don't have it yet. Nor do they
        // have a correction mid-turn, or the part of the reply shown before it.
        val corrected = state.messages.indexOfFirst { it.key == state.correctedReplyKey }
        val live = if (corrected >= 0) {
            state.messages.take(corrected).filter { it.isLocalOnly } + state.messages.drop(corrected)
        } else {
            state.messages.filter { it.isLocalOnly }
        }
        val stored = historyToMessages(rows, rowsSessionId).withRisks(flagged)
        // A prompt sent without a reply that the transcript now has arrived after all.
        val storedPrompts = stored.mapNotNullTo(HashSet()) { (it as? ChatMessage.User)?.text }
        val unsettled = live.filterNot { it is ChatMessage.User && it.check != null && storedPrompts.any { p -> p.contains(it.text) } }
        state.copy(messages = stored + unsettled, historyLoaded = true, historyError = null, olderMessages = older)
    }

    private suspend fun runCatchingAttach(client: JsonRpcClient, reconnected: Boolean = false): Boolean = try {
        val runtimeId = attach(client, reconnected)
        scope.launch { refreshSubagents(client, runtimeId) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update {
            it.copy(
                attachment = Attachment.Failed(e.message ?: "Couldn't open the session."),
                // The gateway itself turned the chat down (gone with its deleted bot, say): no turn of it can be
                // followed, so none shows as running, nor keeps "Working…" in the shade. A lost link isn't that.
                running = it.running && e !is RpcException,
            )
        }
        false
    }

    private suspend fun ensureAttached(client: JsonRpcClient): String {
        _state.value.runtimeSessionId?.let { return it }
        return if (rowExists) attach(client) else create(client)
    }

    /**
     * `session.resume` the stored id; the reply's `session_id` is the runtime id events carry. After a drop
     * ([reconnected]) the same runtime picks up from the last event applied (see [catchUp]); a new one, or one
     * with nothing to pick up from, reloads the transcript instead.
     */
    private suspend fun attach(client: JsonRpcClient, reconnected: Boolean = false): String = attachMutex.withLock {
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
        val streamed = inflight.string("assistant").orEmpty()
        val epoch = readyEpoch(client)
        val (resumable, catchingUp) = streamMutex.withLock {
            inflightText = streamed
            // Seqs count per runtime session and per gateway run, so only the same of both can be picked up.
            val resumable = reconnected && lastSeq != null && runtimeId == seqRuntime && epoch != null && epoch == seqEpoch
            if (!resumable) lastSeq = null
            seqRuntime = runtimeId
            seqEpoch = epoch
            // Held from before the state names the runtime, so no live event slips in ahead of the missed ones.
            val catchingUp = resumable && held == null
            if (catchingUp) held = mutableListOf()
            resumable to catchingUp
        }
        if (reconnected && (!resumable || _state.value.historyError != null)) loadHistory()
        _state.update { state ->
            state.copy(
                attachment = Attachment.Attached(runtimeId),
                running = running,
                // Picking up replays the turn itself; otherwise the reply shows as far as the gateway has it,
                // or, when the turn ended while away, as the transcript just read has it.
                messages = when {
                    resumable -> state.messages
                    running -> state.withInflight(streamed).messages
                    reconnected -> state.withoutStaleReply().messages
                    else -> state.messages
                },
                inputRequests = open.plusNew(claimUnclaimed(runtimeId)),
                keySeq = state.keySeq + 1,
            ).withInfo(result["info"] as? JsonObject).withTodos(TodoList.parse(result["todo_state"] as? JsonObject))
        }
        if (catchingUp) scope.launch { catchUp(client, runtimeId) }
        runtimeId
    }

    /** The gateway run's `replay_epoch` from [client]'s `gateway.ready`, when it is the connected one. */
    private fun readyEpoch(client: JsonRpcClient): String? =
        (connection.state.value as? ConnectionState.Connected)?.takeIf { it.client === client }?.ready.string("replay_epoch")

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
                cwd?.let { put("cwd", it) }
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
        streamMutex.withLock {
            lastSeq = null
            seqRuntime = runtimeId
            seqEpoch = readyEpoch(client)
        }
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

        /** `prompt.submit` statuses for text folded into the running turn (busy mode interrupt or steer). */
        val CORRECTION_STATUSES = setOf("redirected", "steered")

        /** `session.steer`'s status for a steer the running turn took in. */
        const val STEER_ACCEPTED = "queued"

        /** `session.steer` errors for a gateway that can't steer: no such method, or an agent without `steer()`. */
        val STEER_UNSUPPORTED = setOf(METHOD_NOT_FOUND, 4010)

        /** How long Stop & send waits for the stopped turn's `message.complete` before queueing behind it. */
        const val STOP_SETTLE_TIMEOUT_MS = 10_000L

        const val MAX_UNCLAIMED = 8

        /** Bounds [loadAllHistory]: 50 pages of the dashboard's most is 25,000 rows. */
        const val MAX_HISTORY_PAGES = 50

        /** Uploads of several MB over a phone link take a while. */
        const val UPLOAD_TIMEOUT_MS = 120_000L

        const val PDF_RENDER_UNAVAILABLE = 5028

        /** The gateway's "busy": a turn is running, so nothing was cut. */
        const val BUSY = 4009

        const val BUSY_MESSAGE = "Wait for the reply to finish, then try again."

        /**
         * A rewind the gateway can't place: the row isn't in what the live agent holds (4018: compressed
         * away, cut elsewhere), the prompt count drifted (4030), or the target was malformed (4004).
         */
        val STALE_REWIND_CODES = setOf(4018, 4030, 4004)

        const val NOT_CONNECTED = "Not connected to the gateway."

        /** Looks at the transcript for a prompt sent without a reply: about 7 s in all. */
        const val DELIVERY_LOOKS = 4
        const val DELIVERY_LOOK_INTERVAL_MS = 2_500L

        val ANSI_ESCAPE = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

        /** JSON-RPC "method not found": the gateway predates a call. */
        const val METHOD_NOT_FOUND = -32601

        /** A REST read of a session the gateway no longer has. */
        const val HTTP_NOT_FOUND = 404

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

/** How [ChatSession.stopAndSubmit] went: the send's [outcome], and the queued prompts the stop dropped, to give back. */
data class StopAndSend(val outcome: SendOutcome, val dropped: List<String> = emptyList())

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

/**
 * The running turn's reply as the gateway has it so far ([streamed], `inflight.assistant`): it opens the reply when
 * none is, and replaces one that missed part of it. A reply that doesn't lead into it (written since) stays.
 */
internal fun ChatState.withInflight(streamed: String): ChatState {
    val open = messages.indexOfLast { it is ChatMessage.Assistant && it.streaming }
    if (open < 0) {
        return copy(messages = messages + ChatMessage.Assistant(key = "live-$keySeq", text = streamed, streaming = true), keySeq = keySeq + 1)
    }
    val reply = messages[open] as ChatMessage.Assistant
    if (streamed.length <= reply.text.length || !streamed.startsWith(reply.text)) return this
    return copy(messages = messages.toMutableList().apply { set(open, reply.copy(text = streamed)) })
}

/**
 * The reply still marked streaming from a turn that ended while the link was down and couldn't be replayed. The
 * transcript just read holds the turn, so the live copy goes; when it couldn't be read, the copy stays, closed.
 */
internal fun ChatState.withoutStaleReply(): ChatState {
    if (messages.none { it is ChatMessage.Assistant && it.streaming }) return this
    return copy(
        messages = if (historyError == null) {
            messages.filterNot { it is ChatMessage.Assistant && it.streaming }
        } else {
            messages.map { if (it is ChatMessage.Assistant && it.streaming) it.copy(streaming = false) else it }
        },
        correctedReplyKey = null,
    )
}

private fun List<ChatMessage>.updateUser(key: String, change: (ChatMessage.User) -> ChatMessage.User): List<ChatMessage> =
    map { if (it is ChatMessage.User && it.key == key) change(it) else it }

/**
 * Closes the reply streaming above the prompt [key] so the turn's later output opens a new one below
 * it; a reply that had shown nothing yet goes away.
 */
internal fun ChatState.sealReplyBefore(key: String): ChatState {
    val prompt = messages.indexOfFirst { it.key == key }
    val open = messages.indexOfLast { it is ChatMessage.Assistant && it.streaming }
    if (prompt < 0 || open !in 0 until prompt) return this
    val reply = messages[open] as ChatMessage.Assistant
    if (reply.text.isBlank() && reply.reasoning.isBlank() && reply.tools.isEmpty()) {
        return copy(messages = messages.toMutableList().apply { removeAt(open) })
    }
    return copy(
        messages = messages.toMutableList().apply { set(open, reply.copy(streaming = false)) },
        correctedReplyKey = reply.key,
    )
}

/**
 * Messages the stored transcript can't contain yet: the streaming reply and prompts still being sent.
 * A reply that ended with a warning stays too: the warning is usually that it wasn't saved.
 */
private val ChatMessage.isLocalOnly: Boolean
    get() = when (this) {
        is ChatMessage.Assistant -> streaming || warning != null
        // A queued prompt isn't in the transcript until its turn starts.
        is ChatMessage.User -> pending || queued || check != null
        is ChatMessage.Notice -> !stored
        is ChatMessage.Event -> false
        is ChatMessage.Command -> true
    }

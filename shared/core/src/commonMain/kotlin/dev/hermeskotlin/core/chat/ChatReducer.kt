package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Folds one live session event (tui_gateway/contracts/events.py) into the chat. Events for other
 * sessions must be filtered out by the caller. Unknown event types leave the state unchanged.
 * Events carry no time, so [now] (epoch seconds, when it arrived) stamps the reply it finishes.
 */
fun ChatState.reduce(event: GatewayEvent, now: Double? = null): ChatState {
    val payload = event.payload as? JsonObject
    return when (event.type) {
        "message.start" -> releaseQueued().withOpenReply { it }.copy(
            running = true,
            error = null,
            // A new turn hasn't planned yet; the last plan is from before (a correction keeps its turn's plan).
            todosLive = todosLive && running,
            // No totals yet means the agent isn't built, and a new one counts from zero.
            turnStartUsage = turnStartUsage.takeIf { running } ?: usage ?: SessionUsage(),
        )
        "session.usage" -> copy(usage = SessionUsage.parse(payload?.get("usage") as? JsonObject) ?: usage)
        "message.delta" -> appendText(payload.string("text"))
        "message.interim" ->
            // Commentary next to tool calls; when already streamed it is in the text already.
            if (payload.boolean("already_streamed") == true) this else appendText(payload.string("text")?.let { "$it\n\n" })
        "reasoning.delta", "reasoning.available" -> payload.string("text")?.let { chunk ->
            withOpenReply { it.copy(reasoning = it.reasoning + chunk) }
        } ?: this
        // Not reasoning: each frame is the TUI's spinner rewritten ("(>∀<☆)☆ musing..."), so the latest one
        // labels the live activity. An explained provider wait goes to the status line, like Desktop.
        "thinking.delta" -> payload.string("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { frame ->
            if (PROVIDER_WAIT.containsMatchIn(frame)) copy(status = frame) else copy(thinkingFrame = frame)
        } ?: this
        "tool.start" -> {
            val id = payload.string("tool_id") ?: return this
            val tool = ToolActivity(
                id = id,
                name = payload.string("name") ?: "tool",
                detail = payload.string("context") ?: payload.string("preview"),
                running = true,
                input = ToolDetails.input(payload?.get("args"), payload.string("args_text")),
            ).let { if (it.name == DELEGATE_TOOL) it.copy(tasks = delegatedTasks(payload?.get("args"), null)) else it }
            withOpenReply { reply -> reply.copy(tools = reply.tools.filterNot { it.id == id } + tool) }
                .copy(running = true, thinkingFrame = null)
        }
        // The model is still writing the call's arguments; say what's coming.
        "tool.generating" -> payload.string("name")?.let { copy(thinkingFrame = "preparing $it…") } ?: this
        "tool.complete" -> {
            val id = payload.string("tool_id") ?: return this
            val finish = { reply: ChatMessage.Assistant ->
                reply.copy(tools = reply.tools.map {
                    if (it.id != id) it else it.copy(
                        running = false,
                        summary = payload.string("summary"),
                        durationSeconds = payload.double("duration_s"),
                        input = it.input ?: ToolDetails.input(payload?.get("args")),
                        output = ToolDetails.output(payload?.get("result"), payload.string("result_text")),
                        diff = payload.string("inline_diff")?.takeIf { d -> d.isNotBlank() },
                        failed = ToolDetails.failed(payload?.get("result")),
                        tasks = if (it.name != DELEGATE_TOOL) it.tasks else delegatedTasks(
                            args = payload?.get("args"),
                            result = payload?.get("result") ?: payload?.get("result_text") ?: JsonPrimitive(""),
                            knownGoals = it.tasks.map { task -> task.goal },
                        ),
                        delegationIds = if (it.name != DELEGATE_TOOL) it.delegationIds else delegationIds(payload?.get("result") ?: payload?.get("result_text")),
                    )
                })
            }
            // A correction mid-turn closes the reply a tool started in, so look for the tool first.
            val owner = messages.indexOfLast { it is ChatMessage.Assistant && it.tools.any { tool -> tool.id == id } }
            if (owner >= 0) {
                copy(messages = messages.toMutableList().apply { set(owner, finish(get(owner) as ChatMessage.Assistant)) })
            } else {
                withOpenReply(finish)
            }
        }
        "tool.output_risk" -> {
            val (id, risk) = flaggedOutput(payload) ?: return this
            val owner = messages.indexOfLast { it is ChatMessage.Assistant && it.tools.any { tool -> tool.id == id } }
            if (owner < 0) return this
            val reply = messages[owner] as ChatMessage.Assistant
            copy(messages = messages.toMutableList().apply {
                set(owner, reply.copy(tools = reply.tools.map { if (it.id == id) it.copy(risk = risk) else it }))
            })
        }
        "notice" -> payload.string("message")?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            copy(messages = messages + ChatMessage.Notice("notice-$keySeq", text), keySeq = keySeq + 1)
        } ?: this
        "message.complete" -> complete(payload, now)
        "status.update" -> copy(status = payload.string("text")?.takeIf { it.isNotBlank() })
        "error" -> copy(error = payload.string("message"))
        "session.title" -> copy(title = payload.string("title") ?: title)
        "session.info" -> withInfo(payload).copy(title = payload.string("title")?.takeIf { it.isNotBlank() } ?: title)
        "btw.complete" -> answerAside(payload)
        "todo.updated" -> withTodos(TodoList.parse(payload))
        "subagent.spawn_requested", "subagent.start", "subagent.progress", "subagent.thinking", "subagent.tool", "subagent.complete" ->
            withSubagentEvent(event.type, payload)
        else -> this
    }
}

/**
 * A `tool.output_risk` with something found: the call id and why. Every scan of outside content
 * reports, mostly "low"; only a finding is worth a badge.
 */
internal fun flaggedOutput(payload: JsonObject?): Pair<String, ToolRisk>? {
    val id = payload.string("tool_id")?.takeIf { it.isNotEmpty() } ?: return null
    if (payload.string("risk") != "high") return null
    return id to ToolRisk(
        findings = (payload?.get("findings") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        redacted = payload.boolean("redacted") == true,
    )
}

/** Takes a plan snapshot unless an older one arrived late. */
internal fun ChatState.withTodos(list: TodoList?): ChatState {
    if (list == null) return this
    val current = todos
    if (current != null && list.revision < current.revision) return this
    return copy(todos = list, todosLive = running)
}

/** A `/btw` answer: fills the card that asked it, or adds one when the question came from another client. */
private fun ChatState.answerAside(payload: JsonObject?): ChatState {
    val text = payload.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    val taskId = payload.string("task_id")
    val index = messages.indexOfFirst { it is ChatMessage.Command && it.taskId != null && it.taskId == taskId }
    if (index >= 0) {
        val asked = messages[index] as ChatMessage.Command
        return copy(messages = messages.toMutableList().apply { set(index, asked.copy(output = text, running = false)) })
    }
    val question = payload.string("question")?.trim().orEmpty()
    val card = ChatMessage.Command("btw-$keySeq", "/btw $question".trimEnd(), text, running = false, taskId = taskId)
    return copy(messages = messages + card, keySeq = keySeq + 1)
}

/** Desktop's `providerWaitText` (store/provider-wait.ts): the waits the core explains after a long silence. */
private val PROVIDER_WAIT = Regex(
    """^(?:⏳|⚠|↻|⚙)\s*(?:(?:still\s+)?waiting on|loading|processing prompt|no (?:output|response)|model returned|rate limited|provider (?:overloaded|temporarily unavailable))""",
    RegexOption.IGNORE_CASE,
)

/** The model fields of a `session.info` payload (also the `info` of `session.create`/`session.resume`). */
internal fun ChatState.withInfo(info: JsonObject?): ChatState {
    if (info == null) return this
    return copy(
        model = info.string("model")?.takeIf { it.isNotBlank() } ?: model,
        provider = info.string("provider")?.takeIf { it.isNotBlank() } ?: provider,
        // "" means the profile default, which is worth showing as such rather than as a stale pick.
        reasoningEffort = info.string("reasoning_effort")?.let { it.ifBlank { null } } ?: reasoningEffort.takeIf { "reasoning_effort" !in info },
        fast = info.boolean("fast") ?: fast,
        yolo = info.boolean("yolo") ?: yolo,
        usage = SessionUsage.parse(info["usage"] as? JsonObject) ?: usage,
    )
}

private fun ChatState.appendText(chunk: String?): ChatState {
    if (chunk.isNullOrEmpty()) return this
    return withOpenReply { it.copy(text = it.text + chunk) }.copy(running = true)
}

private fun ChatState.complete(payload: JsonObject?, now: Double?): ChatState {
    val outcome = when (payload.string("status")) {
        "error" -> TurnOutcome.Error
        "interrupted" -> TurnOutcome.Interrupted
        else -> TurnOutcome.Complete
    }
    val error = payload.string("error") ?: payload.string("failure_reason")
    val warning = payload.string("warning")?.trim()?.takeIf { it.isNotEmpty() }
    // The turn is over, so no tool runs on, including in a part a correction closed early.
    val messages = messages.map { message ->
        if (message is ChatMessage.Assistant && message.tools.any { it.running }) {
            message.copy(tools = message.tools.map { it.copy(running = false) })
        } else {
            message
        }
    }
    // After a correction, the part above it was already shown and the final text repeats it.
    val shown = (messages.find { it.key == correctedReplyKey } as? ChatMessage.Assistant)?.text?.takeIf { it.isNotBlank() }
    val finalText = payload.string("text").orEmpty().let { text ->
        if (shown != null && text.startsWith(shown)) text.removePrefix(shown).trim() else text
    }
    val finalReasoning = payload.string("reasoning").orEmpty().takeIf { correctedReplyKey == null }.orEmpty()
    val finalUsage = SessionUsage.parse(payload?.get("usage") as? JsonObject)
    val index = messages.openReplyIndex().takeIf { it >= 0 }
        ?: if (finalText.isBlank() && error == null && warning == null) return endTurn(messages, finalUsage) else messages.size
    val base = messages.getOrNull(index) as? ChatMessage.Assistant ?: ChatMessage.Assistant(key = "live-$keySeq")
    val reply = base.copy(
        // Prefer what streamed (it includes interim segments); fall back to the final text for
        // non-streaming providers or when we joined after the deltas.
        text = base.text.ifBlank { finalText },
        reasoning = base.reasoning.ifBlank { finalReasoning },
        tools = base.tools.map { it.copy(running = false) },
        streaming = false,
        outcome = outcome,
        error = error.takeIf { outcome == TurnOutcome.Error },
        usage = finalUsage?.let { end -> turnStartUsage?.let { end - it } }?.takeIf { it.any },
        warning = warning,
        timestamp = now ?: base.timestamp,
    )
    // A turn that ended with nothing to show (e.g. interrupted at once) leaves no bubble, unless it failed or warns.
    val empty = reply.text.isBlank() && reply.reasoning.isBlank() && reply.tools.isEmpty() && reply.error == null && warning == null
    val updated = messages.toMutableList().apply {
        when {
            index == size -> if (!empty) add(reply)
            empty && outcome != TurnOutcome.Error -> removeAt(index)
            else -> set(index, reply)
        }
    }
    return endTurn(updated, finalUsage).copy(keySeq = keySeq + 1)
}

private fun ChatState.endTurn(messages: List<ChatMessage>, finalUsage: SessionUsage?) = copy(
    running = false,
    status = null,
    thinkingFrame = null,
    messages = messages,
    correctedReplyKey = null,
    todosLive = false,
    usage = finalUsage ?: usage,
    turnStartUsage = null,
)

/** The reply still streaming. Not necessarily last: a prompt queued mid-turn sits after it. */
private fun List<ChatMessage>.openReplyIndex(): Int = indexOfLast { it is ChatMessage.Assistant && it.streaming }

/**
 * A turn starting with no reply open is the gateway running what it queued: the oldest waiting prompt
 * goes first. With a reply still streaming, the start belongs to that turn and the queue keeps waiting.
 */
private fun ChatState.releaseQueued(): ChatState {
    if (messages.openReplyIndex() >= 0) return this
    val index = messages.indexOfFirst { it is ChatMessage.User && it.queued }
    if (index < 0) return this
    return copy(messages = messages.toMutableList().apply { set(index, (get(index) as ChatMessage.User).copy(queued = false)) })
}

/** Applies [change] to the reply being streamed, or opens a new one at the end. */
private fun ChatState.withOpenReply(change: (ChatMessage.Assistant) -> ChatMessage.Assistant): ChatState {
    val index = messages.openReplyIndex()
    return if (index >= 0) {
        copy(messages = messages.toMutableList().apply { set(index, change(get(index) as ChatMessage.Assistant)) })
    } else {
        copy(
            messages = messages + change(ChatMessage.Assistant(key = "live-$keySeq", streaming = true)),
            keySeq = keySeq + 1,
        )
    }
}

/** agent/prompt_builder.py's frame around a mid-turn correction, stored as the user row. */
private val CORRECTION_FRAME = Regex("""^\[OUT-OF-BAND USER MESSAGE[^\]]*]\s*([\s\S]*?)\s*\[/OUT-OF-BAND USER MESSAGE]$""")

/** A correction's stored row → what the person typed; anything else unchanged. */
internal fun unwrapCorrection(text: String): String = CORRECTION_FRAME.find(text)?.groupValues?.get(1) ?: text

/**
 * Stored rows → chat messages: tool results and system/hidden rows drop out, and consecutive
 * assistant rows (text and tool-call steps) merge into one reply listing every tool it used.
 * [liveSessionId] is the session the page was read from; prompts stored under an older one can't be cut at.
 */
fun historyToMessages(rows: List<SessionMessage>, liveSessionId: String? = null): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    // Each call's result is a later `tool` row carrying its id.
    val results = rows.filter { it.role == "tool" && it.toolCallId != null }.associateBy { it.toolCallId }
    // Messages this bot sent others (`message_agent`): their delivery process → to whom, to name the outcome.
    val deliveries = rows.asSequence().filter { it.role == "assistant" }.flatMap { it.storedToolCalls }
        .filter { it.name == MESSAGE_AGENT_TOOL }
        .mapNotNull { call -> call.id?.let(results::get)?.text?.let(TranscriptRows::deliveryAck) }
        .toMap()
    // Who this bot has messaged since the person last spoke: their answers aren't replies to fold away.
    val messagedSinceUser = mutableSetOf<String>()
    var replyingTo: String? = null
    rows.forEachIndexed { index, row ->
        if (row.isHidden) return@forEachIndexed
        val key = row.id?.let { "row-$it" } ?: "h$index"
        when (row.role) {
            "user" -> {
                // Background subagents reporting back: written for the agent, so a line here, and their results go to their card.
                parseDelegationReport(row.text.trim())?.let { report ->
                    messages.settle(report)
                    messages += ChatMessage.Notice(key, report.line, stored = true)
                    return@forEachIndexed
                }
                if (TranscriptRows.isNoise(row.text.trim(), row.displayKind)) return@forEachIndexed
                TranscriptRows.classify(row.text, row.displayKind, deliveries)?.let { events ->
                    events.forEachIndexed { i, event ->
                        messages += ChatMessage.Event(if (events.size == 1) key else "$key-$i", event)
                        replyingTo = (event as? TranscriptEvent.FromBot)?.takeUnless { from ->
                            from.handle?.lowercase() in messagedSinceUser || from.sender.lowercase() in messagedSinceUser
                        }?.sender
                    }
                    return@forEachIndexed
                }
                messagedSinceUser.clear()
                replyingTo = null
                val raw = unwrapCorrection(row.text.trim())
                val (refs, text) = skillInvocationText(raw)?.let { emptyList<ShownAttachment>() to it } ?: splitAttachmentRefs(raw, key)
                val attachments = List(row.imageCount) { ShownAttachment("$key-i$it", "Image", AttachmentKind.Image) } + refs
                // Only rows the live agent still holds can be cut at: not those a compression archived or left
                // in the session before it.
                val cuttable = row.active && !row.compacted && (liveSessionId == null || row.sessionId == null || row.sessionId == liveSessionId)
                if (text.isNotEmpty() || attachments.isNotEmpty()) {
                    messages += ChatMessage.User(
                        key,
                        text,
                        attachments = attachments,
                        rowId = row.id?.takeIf { cuttable },
                        // Pictures and files went up once and can't be sent again with the text.
                        sentText = raw.takeIf { attachments.isEmpty() && raw.isNotEmpty() },
                        timestamp = row.timestamp,
                    )
                }
            }
            "assistant" -> {
                if (row.displayKind == "failed_turn") {
                    messages += ChatMessage.Event(key, TranscriptEvent.FailedTurn(row.text.trim()))
                    return@forEachIndexed
                }
                // A bot that chose to say nothing shows nothing.
                val text = row.text.trim().takeUnless(TranscriptRows::isSilence).orEmpty()
                row.storedToolCalls.filter { it.name == MESSAGE_AGENT_TOOL }.forEach { call ->
                    messageTarget(call.arguments)?.let { messagedSinceUser += it.lowercase() }
                }
                val tools = row.storedToolCalls.mapIndexed { i, call ->
                    val result = call.id?.let(results::get)?.content
                    ToolActivity(
                        id = call.id ?: "$key-$i",
                        name = call.name,
                        input = ToolDetails.input(call.arguments),
                        output = ToolDetails.output(result),
                        failed = ToolDetails.failed(result),
                        tasks = if (call.name == DELEGATE_TOOL) delegatedTasks(call.arguments, result) else emptyList(),
                        delegationIds = if (call.name == DELEGATE_TOOL) delegationIds(result) else emptyList(),
                    )
                }
                val reasoning = row.reasoning.orEmpty()
                val previous = messages.lastOrNull() as? ChatMessage.Assistant
                if (previous != null) {
                    messages[messages.lastIndex] = previous.copy(
                        text = listOf(previous.text, text).filter { it.isNotEmpty() }.joinToString("\n\n"),
                        reasoning = listOf(previous.reasoning, reasoning).filter { it.isNotEmpty() }.joinToString("\n\n"),
                        tools = previous.tools + tools,
                        // The reply is dated by its last step, when it finished.
                        timestamp = row.timestamp ?: previous.timestamp,
                    )
                } else if (text.isNotEmpty() || tools.isNotEmpty()) {
                    messages += ChatMessage.Assistant(
                        key,
                        text = text,
                        reasoning = reasoning,
                        tools = tools,
                        repliedTo = replyingTo,
                        timestamp = row.timestamp,
                    )
                }
            }
        }
    }
    return messages
}

/** Bot Mode's tool for messaging another bot. */
const val MESSAGE_AGENT_TOOL = "message_agent"

/** The bot a `message_agent` call is addressed to (`target`, with or without its `@`). */
fun messageTarget(arguments: kotlinx.serialization.json.JsonElement?): String? {
    val obj = when (arguments) {
        is kotlinx.serialization.json.JsonObject -> arguments
        is kotlinx.serialization.json.JsonPrimitive -> arguments.contentOrNull?.let {
            runCatching { dev.hermeskotlin.core.network.HermesJson.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull()
        }
        else -> null
    } ?: return null
    return (obj["target"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.removePrefix("@")?.substringBefore('@')?.takeIf { it.isNotEmpty() }
}

package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonObject

/**
 * Folds one live session event (tui_gateway/contracts/events.py) into the chat. Events for other
 * sessions must be filtered out by the caller. Unknown event types leave the state unchanged.
 */
fun ChatState.reduce(event: GatewayEvent): ChatState {
    val payload = event.payload as? JsonObject
    return when (event.type) {
        "message.start" -> withOpenReply { it }.copy(running = true, error = null)
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
            )
            withOpenReply { reply -> reply.copy(tools = reply.tools.filterNot { it.id == id } + tool) }.copy(running = true)
        }
        "tool.complete" -> {
            val id = payload.string("tool_id") ?: return this
            val finish = { reply: ChatMessage.Assistant ->
                reply.copy(tools = reply.tools.map {
                    if (it.id != id) it else it.copy(
                        running = false,
                        summary = payload.string("summary"),
                        durationSeconds = payload.double("duration_s"),
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
        "message.complete" -> complete(payload)
        "status.update" -> copy(status = payload.string("text")?.takeIf { it.isNotBlank() })
        "error" -> copy(error = payload.string("message"))
        "session.title" -> copy(title = payload.string("title") ?: title)
        "session.info" -> withInfo(payload).copy(title = payload.string("title")?.takeIf { it.isNotBlank() } ?: title)
        "btw.complete" -> answerAside(payload)
        "todo.updated" -> withTodos(TodoList.parse(payload))
        else -> this
    }
}

/** Takes a plan snapshot unless an older one arrived late. */
internal fun ChatState.withTodos(list: TodoList?): ChatState {
    if (list == null) return this
    val current = todos
    if (current != null && list.revision < current.revision) return this
    return copy(todos = list)
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
    )
}

private fun ChatState.appendText(chunk: String?): ChatState {
    if (chunk.isNullOrEmpty()) return this
    return withOpenReply { it.copy(text = it.text + chunk) }.copy(running = true)
}

private fun ChatState.complete(payload: JsonObject?): ChatState {
    val outcome = when (payload.string("status")) {
        "error" -> TurnOutcome.Error
        "interrupted" -> TurnOutcome.Interrupted
        else -> TurnOutcome.Complete
    }
    val error = payload.string("error") ?: payload.string("failure_reason")
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
    val index = messages.openReplyIndex().takeIf { it >= 0 }
        ?: if (finalText.isBlank() && error == null) {
            return copy(running = false, status = null, thinkingFrame = null, messages = messages, correctedReplyKey = null, todos = todosAfterTurn())
        } else {
            messages.size
        }
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
    )
    // A turn that ended with nothing to show (e.g. interrupted at once) leaves no bubble, unless it failed.
    val empty = reply.text.isBlank() && reply.reasoning.isBlank() && reply.tools.isEmpty() && reply.error == null
    val updated = messages.toMutableList().apply {
        when {
            index == size -> if (!empty) add(reply)
            empty && outcome != TurnOutcome.Error -> removeAt(index)
            else -> set(index, reply)
        }
    }
    return copy(
        running = false,
        status = null,
        thinkingFrame = null,
        messages = updated,
        keySeq = keySeq + 1,
        correctedReplyKey = null,
        todos = todosAfterTurn(),
    )
}

/** A plan still open when its turn ends was abandoned (stopped, or no final update); a finished one stays. */
private fun ChatState.todosAfterTurn(): TodoList? = todos?.takeUnless { it.active }

/** The reply still streaming. Not necessarily last: a prompt queued mid-turn sits after it. */
private fun List<ChatMessage>.openReplyIndex(): Int = indexOfLast { it is ChatMessage.Assistant && it.streaming }

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
 */
fun historyToMessages(rows: List<SessionMessage>): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    rows.forEachIndexed { index, row ->
        if (row.isHidden) return@forEachIndexed
        val key = row.id?.let { "row-$it" } ?: "h$index"
        when (row.role) {
            "user" -> {
                val raw = unwrapCorrection(row.text.trim())
                val (refs, text) = skillInvocationText(raw)?.let { emptyList<ShownAttachment>() to it } ?: splitAttachmentRefs(raw, key)
                val attachments = List(row.imageCount) { ShownAttachment("$key-i$it", "Image", AttachmentKind.Image) } + refs
                if (text.isNotEmpty() || attachments.isNotEmpty()) messages += ChatMessage.User(key, text, attachments = attachments)
            }
            "assistant" -> {
                val text = row.text.trim()
                val tools = row.calledTools.mapIndexed { i, name -> ToolActivity(id = "$key-$i", name = name) }
                val reasoning = row.reasoning.orEmpty()
                val previous = messages.lastOrNull() as? ChatMessage.Assistant
                if (previous != null) {
                    messages[messages.lastIndex] = previous.copy(
                        text = listOf(previous.text, text).filter { it.isNotEmpty() }.joinToString("\n\n"),
                        reasoning = listOf(previous.reasoning, reasoning).filter { it.isNotEmpty() }.joinToString("\n\n"),
                        tools = previous.tools + tools,
                    )
                } else if (text.isNotEmpty() || tools.isNotEmpty()) {
                    messages += ChatMessage.Assistant(key, text = text, reasoning = reasoning, tools = tools)
                }
            }
        }
    }
    return messages
}

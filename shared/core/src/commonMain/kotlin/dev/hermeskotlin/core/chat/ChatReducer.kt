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
        "reasoning.delta", "thinking.delta", "reasoning.available" -> payload.string("text")?.let { chunk ->
            withOpenReply { it.copy(reasoning = it.reasoning + chunk) }
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
            withOpenReply { reply ->
                reply.copy(tools = reply.tools.map {
                    if (it.id != id) it else it.copy(
                        running = false,
                        summary = payload.string("summary"),
                        durationSeconds = payload.double("duration_s"),
                    )
                })
            }
        }
        "message.complete" -> complete(payload)
        "status.update" -> copy(status = payload.string("text")?.takeIf { it.isNotBlank() })
        "error" -> copy(error = payload.string("message"))
        "session.title" -> copy(title = payload.string("title") ?: title)
        "session.info" -> withInfo(payload).copy(title = payload.string("title")?.takeIf { it.isNotBlank() } ?: title)
        else -> this
    }
}

/** The model fields of a `session.info` payload (also the `info` of `session.create`/`session.resume`). */
internal fun ChatState.withInfo(info: JsonObject?): ChatState {
    if (info == null) return this
    return copy(
        model = info.string("model")?.takeIf { it.isNotBlank() } ?: model,
        provider = info.string("provider")?.takeIf { it.isNotBlank() } ?: provider,
        // "" means the profile default, which is worth showing as such rather than as a stale pick.
        reasoningEffort = info.string("reasoning_effort")?.let { it.ifBlank { null } } ?: reasoningEffort.takeIf { "reasoning_effort" !in info },
        fast = info.boolean("fast") ?: fast,
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
    val finalText = payload.string("text").orEmpty()
    val finalReasoning = payload.string("reasoning").orEmpty()
    val index = messages.openReplyIndex().takeIf { it >= 0 }
        ?: if (finalText.isBlank() && error == null) return copy(running = false, status = null) else messages.size
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
    return copy(running = false, status = null, messages = updated, keySeq = keySeq + 1)
}

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
                val (files, text) = splitFileRefs(row.text.trim())
                val attachments = List(row.imageCount) { ShownAttachment("$key-i$it", "Image", AttachmentKind.Image) } +
                    files.mapIndexed { i, name -> ShownAttachment("$key-f$i", name, if (name.endsWith(".pdf", true)) AttachmentKind.Pdf else AttachmentKind.File) }
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

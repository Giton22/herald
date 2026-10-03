package dev.hermeskotlin.core.chat

/** One tool call inside an assistant reply. [running] until `tool.complete` arrives. */
data class ToolActivity(
    val id: String,
    val name: String,
    /** Short description of what it is doing (`context`/`preview`), when the gateway sends one. */
    val detail: String? = null,
    val running: Boolean = false,
    val summary: String? = null,
    val durationSeconds: Double? = null,
    /** What it was given: a command or query as itself, else the arguments as JSON. */
    val input: String? = null,
    /** What it gave back, clipped. */
    val output: String? = null,
    /** The edit a file tool made, as a unified diff. */
    val diff: String? = null,
    /** It reported an error (the turn may still have carried on). */
    val failed: Boolean = false,
)

enum class TurnOutcome { Complete, Interrupted, Error }

sealed interface ChatMessage {
    val key: String

    /** [pending] while `prompt.submit` has not answered; [queued] when the gateway held it behind a running turn. */
    data class User(
        override val key: String,
        val text: String,
        val pending: Boolean = false,
        val queued: Boolean = false,
        val attachments: List<ShownAttachment> = emptyList(),
    ) : ChatMessage

    /** One reply: streamed text, reasoning and the tools it ran. [streaming] until `message.complete`. */
    data class Assistant(
        override val key: String,
        val text: String = "",
        val reasoning: String = "",
        val tools: List<ToolActivity> = emptyList(),
        val streaming: Boolean = false,
        val outcome: TurnOutcome? = null,
        val error: String? = null,
        /** Tokens the turn took; only for turns watched live (the transcript doesn't keep it). */
        val usage: TurnUsage? = null,
    ) : ChatMessage

    /**
     * A slash command and what it printed, shown on this device only (the transcript never holds it).
     * [running] until the gateway answers; [failed] when [output] is an error.
     */
    data class Command(
        override val key: String,
        val command: String,
        val output: String = "",
        val running: Boolean = true,
        val failed: Boolean = false,
        /** The side task answering a `/btw`, whose `btw.complete` fills [output] in. */
        val taskId: String? = null,
    ) : ChatMessage
}

sealed interface Attachment {
    /** Nothing live yet: a new chat (created on first send) or waiting for the socket. */
    data object Detached : Attachment

    data object Attaching : Attachment

    data class Attached(val runtimeSessionId: String) : Attachment

    data class Failed(val message: String) : Attachment
}

data class ChatState(
    /** The durable `sessions` row id; null for a new chat until `session.create` answers. */
    val storedSessionId: String? = null,
    val title: String? = null,
    val model: String? = null,
    /** Provider slug of [model]. */
    val provider: String? = null,
    /** `none`, a level such as `medium`, or null for the profile default (see ReasoningEffort). */
    val reasoningEffort: String? = null,
    /** Priority tier on; null until the gateway reports it. */
    val fast: Boolean? = null,
    /** Tool approvals skipped for this chat (`/yolo`); null until the gateway reports it. */
    val yolo: Boolean? = null,
    val historyLoaded: Boolean = false,
    val historyError: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val attachment: Attachment = Attachment.Detached,
    /** A turn is running on the gateway (ours or one started elsewhere). */
    val running: Boolean = false,
    /** Transient `status.update` line while a turn runs. */
    val status: String? = null,
    /** The latest `thinking.delta` spinner frame ("(⌐■_■) formulating..."), the TUI's live activity cue. */
    val thinkingFrame: String? = null,
    /** Session-level failure outside a turn (`error` event, failed send). */
    val error: String? = null,
    /** Questions the agent is blocked on (approval, clarify, sudo, secret), oldest first. */
    val inputRequests: List<InputRequest> = emptyList(),
    /** The live agent's latest token totals. */
    val usage: SessionUsage? = null,
    /** [usage] when the running turn started, to tell what the turn itself took. */
    val turnStartUsage: SessionUsage? = null,
    /** The agent's plan for the running turn; a finished one stays until the next plan or chat. */
    val todos: TodoList? = null,
    /** The part of the running turn's reply shown before a mid-turn correction; the turn continues below it. */
    val correctedReplyKey: String? = null,
    /** Source of unique keys for messages created on this device. */
    val keySeq: Int = 0,
) {
    val runtimeSessionId: String? get() = (attachment as? Attachment.Attached)?.runtimeSessionId

    /** Prompts or replies exist, so the stored row does too; command output alone doesn't make one. */
    val hasConversation: Boolean get() = messages.any { it !is ChatMessage.Command }
}

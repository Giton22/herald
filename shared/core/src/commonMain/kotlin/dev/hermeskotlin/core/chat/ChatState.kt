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
    val historyLoaded: Boolean = false,
    val historyError: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val attachment: Attachment = Attachment.Detached,
    /** A turn is running on the gateway (ours or one started elsewhere). */
    val running: Boolean = false,
    /** Transient `status.update` line while a turn runs. */
    val status: String? = null,
    /** Session-level failure outside a turn (`error` event, failed send). */
    val error: String? = null,
    /** Questions the agent is blocked on (approval, clarify, sudo, secret), oldest first. */
    val inputRequests: List<InputRequest> = emptyList(),
    /** Source of unique keys for messages created on this device. */
    val keySeq: Int = 0,
) {
    val runtimeSessionId: String? get() = (attachment as? Attachment.Attached)?.runtimeSessionId
}

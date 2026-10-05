package dev.hermeskotlin.core.chat

import kotlinx.serialization.Serializable

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
    /** Its output was scanned and looked like a prompt injection or a leaked secret. */
    val risk: ToolRisk? = null,
    /** For `delegate_task`: the tasks it handed out to subagents. */
    val tasks: List<DelegatedTask> = emptyList(),
    /** For a `delegate_task` sent to the background: the ids its results are reported under later. */
    val delegationIds: List<String> = emptyList(),
)

/** Why a tool's output was flagged (`tool.output_risk`): the scanner's finding ids. Advisory; nothing was blocked. */
@Serializable
data class ToolRisk(val findings: List<String>, val redacted: Boolean = false) {
    /** The ids (tools/threat_patterns.py, e.g. `exfil_curl`) as readable words: "Exfil curl". */
    val labels: List<String>
        get() = findings.map { id -> id.replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() } }.distinct()
}

enum class TurnOutcome { Complete, Interrupted, Error }

/** A prompt that went out but got no reply: the link dropped or the gateway didn't answer in time. */
enum class SendCheck {
    /** Looking for it in the stored transcript. */
    Checking,

    /** The transcript couldn't tell: it may be running, so it shouldn't simply be sent again. */
    Unknown,

    /** A later check found it missing from the transcript: Hermes never got it, so it's safe to resend. */
    NotReceived,
}

/** How a send ended. */
enum class SendOutcome {
    Sent,

    /** It never reached Hermes; the bubble is gone, so the text should go back to the composer. */
    NotSent,

    /** It went out without a reply and its bubble stays, marked by a [SendCheck], to check or resend from. */
    Unsettled,
}

/** What went out for a bubble marked by a [SendCheck]: [text] as sent, [display] as shown. */
internal class UnsettledPrompt(val text: String, val display: String?, val startsTurn: Boolean)

sealed interface ChatMessage {
    val key: String

    /**
     * [pending] while `prompt.submit` has not answered; [queued] when the gateway held it behind a running
     * turn; [check] when it went out without a reply.
     */
    data class User(
        override val key: String,
        val text: String,
        val pending: Boolean = false,
        val queued: Boolean = false,
        val attachments: List<ShownAttachment> = emptyList(),
        val check: SendCheck? = null,
        /** Its stored row (`messages.id`), where a regenerate or an edit cuts the chat; null when it can't be cut there. */
        val rowId: Long? = null,
        /** What went out for it, to send again; null when that can't be had (pictures or files rode along). */
        val sentText: String? = null,
        /** When it was sent, in epoch seconds: the stored row's time, or when this device sent it. */
        val timestamp: Double? = null,
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
        /** Something the gateway wants known about the finished turn, e.g. that it wasn't saved. */
        val warning: String? = null,
        /** The bot whose message this answered; such a reply folds away under it, as on Desktop. */
        val repliedTo: String? = null,
        /**
         * When it finished, in epoch seconds: its last stored row's time, or when this device saw it end
         * (live events carry no time). Null while it streams.
         */
        val timestamp: Double? = null,
    ) : ChatMessage

    /** A row written by the machinery rather than the person: another bot's message, a delivery, a routine… */
    data class Event(override val key: String, val event: TranscriptEvent) : ChatMessage

    /**
     * A one-line notice about the session: a live `notice`, shown on this device only, or a [stored] row
     * the transcript keeps for the agent (e.g. background subagents reporting back) put in a line.
     */
    data class Notice(override val key: String, val text: String, val stored: Boolean = false) : ChatMessage

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
    /** The transcript goes back further than what's loaded; scrolling to the top loads the page before. */
    val olderMessages: Boolean = false,
    /** An older page is being read. */
    val loadingOlder: Boolean = false,
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
    /** The agent's latest plan: live while its turn runs, kept afterwards as the last plan (done or not). */
    val todos: TodoList? = null,
    /** [todos] was written by the turn running now, rather than kept from an earlier one. */
    val todosLive: Boolean = false,
    /** Subagents seen on this device, nested ones included; see [subagentRows]. */
    val subagents: List<Subagent> = emptyList(),
    /** The part of the running turn's reply shown before a mid-turn correction; the turn continues below it. */
    val correctedReplyKey: String? = null,
    /** Source of unique keys for messages created on this device. */
    val keySeq: Int = 0,
) {
    val runtimeSessionId: String? get() = (attachment as? Attachment.Attached)?.runtimeSessionId

    /** Prompts or replies exist, so the stored row does too; command output or notices alone don't make one. */
    val hasConversation: Boolean get() = messages.any { it is ChatMessage.User || it is ChatMessage.Assistant }
}

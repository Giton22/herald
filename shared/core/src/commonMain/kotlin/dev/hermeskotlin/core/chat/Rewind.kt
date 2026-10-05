package dev.hermeskotlin.core.chat

/*
 * Regenerate and edit both rewind the chat: one `prompt.submit` that cuts the stored transcript before a
 * prompt's row (`truncate_before_row_id`, with `confirm_truncate`) and sends a prompt in its place, as
 * Hermes Desktop does. These say which messages that can start from and what it throws away.
 */

/** The prompt a regenerate of reply [replyKey] sends again: the one it answered, when that can be cut at and resent. */
fun ChatState.regenerateTarget(replyKey: String): ChatMessage.User? {
    val index = messages.indexOfFirst { it.key == replyKey }
    if (index < 0 || messages[index] !is ChatMessage.Assistant) return null
    val prompt = messages.subList(0, index).lastOrNull { it is ChatMessage.User } as? ChatMessage.User ?: return null
    return prompt.takeIf { it.rewindable && it.sentText != null }
}

/** Whether prompt [key] can be edited in place: it can be cut at, and what it shows is what went out. */
fun ChatState.canEdit(key: String): Boolean {
    val prompt = messages.firstOrNull { it.key == key } as? ChatMessage.User ?: return false
    return prompt.rewindable && prompt.sentText != null && prompt.sentText == prompt.text
}

/**
 * How many messages after prompt [key]'s own exchange a rewind there throws away: the later prompts, the
 * replies to them. The prompt and its reply are replaced anyway.
 */
fun ChatState.discardedAfter(key: String): Int {
    val index = messages.indexOfFirst { it.key == key }
    if (index < 0) return 0
    val nextPrompt = (index + 1 until messages.size).firstOrNull { messages[it] is ChatMessage.User } ?: return 0
    return messages.subList(nextPrompt, messages.size).count { it is ChatMessage.User || it is ChatMessage.Assistant }
}

/** It has a stored row the gateway can cut at, and it settled: not on its way, queued, or in doubt. */
private val ChatMessage.User.rewindable: Boolean
    get() = rowId != null && !pending && !queued && check == null

package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.historyToMessages
import dev.hermeskotlin.core.sessions.SessionMessage

/** A chat's last exchange, for a watch opening it: what was last said ([said], null when nothing was) and the reply after it. */
data class LastExchange(val said: String?, val reply: String)

/**
 * The last exchange in a chat's stored [rows]: its last reply and the prompt it answered. A prompt still waiting for
 * its reply (a turn running elsewhere) is passed over for the exchange before it. Null when there's no reply at all.
 */
fun lastExchange(rows: List<SessionMessage>): LastExchange? {
    val messages = historyToMessages(rows)
    val replyAt = messages.indexOfLast { it is ChatMessage.Assistant && it.text.isNotBlank() }
    val reply = messages.getOrNull(replyAt) as? ChatMessage.Assistant ?: return null
    val said = messages.take(replyAt).lastOrNull { it is ChatMessage.User } as? ChatMessage.User
    return LastExchange(said?.text?.takeIf { it.isNotBlank() }, reply.text)
}

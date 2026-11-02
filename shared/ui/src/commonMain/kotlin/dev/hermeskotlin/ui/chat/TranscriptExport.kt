package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.TranscriptEvent
import dev.hermeskotlin.ui.i18n.Strings

/** The conversation as Markdown: each prompt and reply under its speaker, attachments listed by name. */
internal fun transcriptMarkdown(title: String, messages: List<ChatMessage>): String = buildString {
    appendLine("# $title")
    messages.forEach { message ->
        when (message) {
            is ChatMessage.User -> {
                appendLine()
                appendLine(Strings.t("## You"))
                appendLine()
                if (message.text.isNotBlank()) appendLine(message.text.trim())
                message.attachments.forEach { appendLine(Strings.t("- Attached: {0}", it.name)) }
            }
            is ChatMessage.Assistant -> if (message.text.isNotBlank()) {
                appendLine()
                appendLine("## Hermes")
                appendLine()
                appendLine(message.text.trim())
            }
            is ChatMessage.Event -> (message.event as? TranscriptEvent.FromBot)?.let { from ->
                appendLine()
                appendLine(Strings.t("## {0} (bot)", from.sender))
                appendLine()
                appendLine(from.body.trim())
            }
            // Command output never reached the transcript; an export matches what the gateway stored.
            is ChatMessage.Command, is ChatMessage.Notice -> Unit
        }
    }
}

/** `my-title-1a2b3c4d.md`, like Desktop's export names. */
internal fun transcriptFileName(title: String, sessionId: String): String {
    fun slug(value: String) = value.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(48)
    val name = slug(title).ifEmpty { Strings.t("session") }
    val id = slug(sessionId).take(8).ifEmpty { Strings.t("session") }
    return "$name-$id.md"
}

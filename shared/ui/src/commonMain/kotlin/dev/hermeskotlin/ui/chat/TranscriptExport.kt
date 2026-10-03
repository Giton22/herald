package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage

/** The conversation as Markdown: each prompt and reply under its speaker, attachments listed by name. */
internal fun transcriptMarkdown(title: String, messages: List<ChatMessage>): String = buildString {
    appendLine("# $title")
    messages.forEach { message ->
        when (message) {
            is ChatMessage.User -> {
                appendLine()
                appendLine("## You")
                appendLine()
                if (message.text.isNotBlank()) appendLine(message.text.trim())
                message.attachments.forEach { appendLine("- Attached: ${it.name}") }
            }
            is ChatMessage.Assistant -> if (message.text.isNotBlank()) {
                appendLine()
                appendLine("## Hermes")
                appendLine()
                appendLine(message.text.trim())
            }
        }
    }
}

/** `my-title-1a2b3c4d.md`, like Desktop's export names. */
internal fun transcriptFileName(title: String, sessionId: String): String {
    fun slug(value: String) = value.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(48)
    val name = slug(title).ifEmpty { "session" }
    val id = slug(sessionId).take(8).ifEmpty { "session" }
    return "$name-$id.md"
}

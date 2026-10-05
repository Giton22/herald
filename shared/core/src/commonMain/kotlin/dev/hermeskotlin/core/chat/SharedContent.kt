package dev.hermeskotlin.core.chat

/**
 * What another app shared with Herald (Android's share sheet), as read off its intent: the text and
 * subject it sent, and the files (content URIs) it attached. Becomes a draft in a new chat, never a
 * message on its own.
 */
data class SharedContent(
    val text: String? = null,
    val subject: String? = null,
    val files: List<String> = emptyList(),
) {
    /**
     * The composer text: what was shared, with its subject on top when that adds something, the way a
     * browser shares a page's title with its link. Null when there's no text.
     */
    val draftText: String?
        get() {
            val body = text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT_CHARS)
            val title = subject?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                body == null -> title
                title == null || body.contains(title) || !body.isLink() -> body
                else -> "$title\n$body"
            }
        }

    /** The files to attach, each once, as many as a message takes. */
    val filesToAttach: List<String>
        get() = files.distinct().take(OutgoingAttachment.MAX_COUNT)

    /** A sentence for files left behind over the per-message limit, or null when all fit. */
    val leftOverNotice: String?
        get() = if (files.distinct().size > OutgoingAttachment.MAX_COUNT) {
            "Only the first ${OutgoingAttachment.MAX_COUNT} files were attached."
        } else {
            null
        }

    val isEmpty: Boolean get() = draftText == null && files.isEmpty()

    private fun String.isLink(): Boolean = '\n' !in this && ' ' !in this && LINK.matches(this)

    companion object {
        /** Long enough for an article pasted whole; a runaway share doesn't freeze the composer. */
        const val MAX_TEXT_CHARS = 100_000

        private val LINK = Regex("""(?i)https?://\S+""")
    }
}

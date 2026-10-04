package dev.hermeskotlin.ui.assistant

import dev.hermeskotlin.core.chat.OutgoingAttachment

/**
 * What was on the phone's screen when the assistant was called up: the app in front, the text it showed
 * (the platform's view of the window, in reading order) and a screenshot when the user lets the
 * assistant have one. Either half may be missing: a secure window gives neither.
 */
class ScreenContext(
    /** The app's name as the launcher shows it, or its package when there is no label. */
    val app: String?,
    val lines: List<String>,
    /** JPEG bytes. */
    val screenshot: ByteArray?,
) {
    val isEmpty: Boolean get() = lines.isEmpty() && screenshot == null

    /**
     * The screen as prompt attachments: the screenshot as an image the model looks at, and the text as a
     * file the gateway expands into the prompt. The text says what it is, since the model sees it cold.
     */
    fun toAttachments(): List<OutgoingAttachment> = buildList {
        // Already a small JPEG, so it is its own thumbnail.
        screenshot?.let { add(OutgoingAttachment("screen-shot", SCREENSHOT_NAME, "image/jpeg", it, thumbnail = it)) }
        screenText()?.let { add(OutgoingAttachment("screen-text", TEXT_NAME, "text/plain", it.encodeToByteArray())) }
    }

    /** The text file's contents, or null when the screen gave no text. */
    fun screenText(): String? {
        val kept = keptLines()
        if (kept.isEmpty()) return null
        return buildString {
            append("Text on the user's phone screen")
            app?.let { append(" in ").append(it) }
            append(" when they asked. ")
            append(if (screenshot != null) "A screenshot of the same screen is attached." else "There is no screenshot.")
            append("\n\n")
            var size = length
            for ((index, line) in kept.withIndex()) {
                if (size + line.length + 1 > MAX_TEXT_CHARS) {
                    append("[… ${kept.size - index} more lines cut]\n")
                    break
                }
                append(line).append('\n')
                size += line.length + 1
            }
        }.trimEnd()
    }

    /** Lines worth reading: trimmed, blank ones dropped, and a line repeated right after itself (a label and its content description) once. */
    private fun keptLines(): List<String> {
        val out = ArrayList<String>(lines.size)
        for (raw in lines) {
            val line = raw.lines().joinToString(" ") { it.trim() }.trim()
            if (line.isEmpty() || out.lastOrNull() == line) continue
            out += line
        }
        return out
    }

    companion object {
        const val SCREENSHOT_NAME = "screen.jpg"
        const val TEXT_NAME = "screen.txt"

        /** A long feed or document is cut here: the model gets the screenshot for the rest. */
        const val MAX_TEXT_CHARS = 20_000
    }
}

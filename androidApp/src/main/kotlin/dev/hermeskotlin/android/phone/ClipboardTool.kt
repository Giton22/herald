package dev.hermeskotlin.android.phone

import android.content.ClipData
import android.content.ClipboardManager
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.forQuestion
import dev.hermeskotlin.core.phone.hidden
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `phone_clipboard`: puts the agent's text on the clipboard, ready to paste, when enabled. Writing only:
 * what's on the clipboard (a password, a code) is never read back to the gateway, and Android wouldn't let an app in
 * the background read it anyway.
 */
internal class ClipboardTool(private val confirmations: PhoneConfirmations) {

    suspend fun run(args: JsonObject): JsonObject {
        val text = args.string("text")?.takeIf { it.isNotBlank() } ?: throw PhoneToolException("text is needed.")
        // All of it in the question: a long text could hide what gets pasted (a command, say) past what's shown.
        val length = text.codePointCount(0, text.length)
        if (length > MAX_TEXT) throw PhoneToolException("text is at most $MAX_TEXT characters, so the user can read all of it first.")
        // Line breaks (either kind) and tabs are what code and drafts are made of; anything that hides what's pasted
        // is refused. Copied as sent: the check alone sees them as plain.
        if (hidden(text.replace("\r\n", "\n").replace('\t', ' '), newlines = true)) {
            throw PhoneToolException("text has hidden characters or odd spaces in it.")
        }
        confirmations.confirm(PhoneTool.Clipboard, "Copy to the clipboard: ${forQuestion(text.replace("\r\n", "\n"))}", TITLE) { activity ->
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
                ?: throw PhoneToolException("The phone has no clipboard.")
            clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, text))
        }
        return buildJsonObject {
            put("copied", true)
            put("characters", length)
            put("note", "The text is on the clipboard, ready to paste.")
        }
    }

    private companion object {
        /** What the question shows whole, as Open's share does. */
        const val MAX_TEXT = 300
        const val LABEL = "From the agent"
        const val TITLE = "The agent wants to copy something to your clipboard"
    }
}

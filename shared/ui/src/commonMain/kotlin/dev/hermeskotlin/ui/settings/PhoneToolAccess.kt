package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import dev.hermeskotlin.core.phone.PhoneTool

/** What a phone tool needs from the platform before it can work: a permission, or a special access in its settings. */
interface PhoneToolAccess {
    /** What [tool] still needs, as a sentence for under its switch; null when it has everything. */
    fun missing(tool: PhoneTool): String?

    /** Asks for it: the permission prompt, or the settings page where the user grants it. */
    fun request(tool: PhoneTool)

    /**
     * A grant [tool] works without but does more with, as the label of the row that asks for it (location all the
     * time, so it also works while Herald is closed); null when there's none or it's granted. Never asked unprompted.
     */
    fun optional(tool: PhoneTool): String? = null

    /** Asks for [optional]'s grant, when the user taps its row. */
    fun requestOptional(tool: PhoneTool) {}

    /** What ringing for an answer still needs (notifications, full-screen calls), as a sentence; null when nothing. */
    fun ringsMissing(): String? = null

    /** Asks for [ringsMissing]'s grant. */
    fun requestRings() {}
}

/** Reads again when the user comes back to the app, so a grant made in the system settings shows at once. */
@Composable
expect fun rememberPhoneToolAccess(): PhoneToolAccess

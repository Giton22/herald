package dev.hermeskotlin.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.CornerDownRight
import com.composables.icons.lucide.ListEnd
import com.composables.icons.lucide.Lucide
import dev.hermeskotlin.core.settings.RunningSend
import dev.hermeskotlin.ui.i18n.Strings

/**
 * How a send goes: null when no reply is [running] (a plain prompt), else the mode [picked] for this message
 * or the [setting]. A steer can't carry files, so one [withAttachments] is queued instead.
 */
internal fun sendModeFor(running: Boolean, picked: RunningSend?, setting: RunningSend?, withAttachments: Boolean): RunningSend? {
    if (!running) return null
    val mode = picked ?: setting ?: RunningSend.Steer
    return if (mode == RunningSend.Steer && withAttachments) RunningSend.Queue else mode
}

/** The name a send mode goes by in the Send menu and Settings. */
internal val RunningSend.label: String
    get() = when (this) {
        RunningSend.Steer -> Strings.t("Steer")
        RunningSend.Queue -> Strings.t("Queue")
        RunningSend.StopAndSend -> Strings.t("Stop & send")
    }

/** What the mode does to the running reply, in a few words. */
internal val RunningSend.summary: String
    get() = when (this) {
        RunningSend.Steer -> Strings.t("Adds it to the running reply")
        RunningSend.Queue -> Strings.t("Sends it once the reply ends")
        RunningSend.StopAndSend -> Strings.t("Stops the reply, then sends it")
    }

internal val RunningSend.icon: ImageVector
    get() = when (this) {
        RunningSend.Steer -> Lucide.CornerDownRight
        RunningSend.Queue -> Lucide.ListEnd
        RunningSend.StopAndSend -> Lucide.CircleStop
    }

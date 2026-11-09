package dev.hermeskotlin.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.CornerDownRight
import com.composables.icons.lucide.ListEnd
import com.composables.icons.lucide.Lucide
import dev.hermeskotlin.core.settings.RunningSend
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * How a send goes: null when no reply is [running] (a plain prompt), else the mode [picked] for this message
 * or the [setting]. A steer can't carry files, so one [withAttachments] is queued instead.
 */
internal fun sendModeFor(running: Boolean, picked: RunningSend?, setting: RunningSend?, withAttachments: Boolean): RunningSend? {
    if (!running) return null
    val mode = picked ?: setting ?: RunningSend.Steer
    return if (mode == RunningSend.Steer && withAttachments) RunningSend.Queue else mode
}

/** The name a send mode goes by in the Send menu, the same key Settings names it with. */
internal val RunningSend.label: StringResource
    get() = when (this) {
        RunningSend.Steer -> Res.string.running_send_steer_label
        RunningSend.Queue -> Res.string.running_send_queue_label
        RunningSend.StopAndSend -> Res.string.running_send_stop_and_send_label
    }

/** What the mode does to the running reply, in a few words. */
internal val RunningSend.summary: StringResource
    get() = when (this) {
        RunningSend.Steer -> Res.string.running_send_steer
        RunningSend.Queue -> Res.string.running_send_queue
        RunningSend.StopAndSend -> Res.string.running_send_stop_and_send
    }

internal val RunningSend.icon: ImageVector
    get() = when (this) {
        RunningSend.Steer -> Lucide.CornerDownRight
        RunningSend.Queue -> Lucide.ListEnd
        RunningSend.StopAndSend -> Lucide.CircleStop
    }

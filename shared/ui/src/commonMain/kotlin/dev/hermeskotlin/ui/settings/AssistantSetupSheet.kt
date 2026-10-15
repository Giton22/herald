package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.bots.ASSISTANT_OPTIONAL_TOOLSET
import dev.hermeskotlin.core.bots.ASSISTANT_PROFILE
import dev.hermeskotlin.core.bots.ASSISTANT_TOOLSETS
import dev.hermeskotlin.core.bots.BotDraft
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.TextField

/** What each toolset is called in setup, in the order the switches show. */
private val TOOLSET_LABELS = linkedMapOf(
    "web" to ("Web search" to "Look things up on the web and read pages."),
    "memory" to ("Memory" to "What the assistant remembers about you."),
    "session_search" to ("Past chats" to "Find things said in earlier conversations."),
    "todo" to ("To-do list" to "Keep track of things you ask it to remember to do."),
    "clarify" to ("Questions" to "Ask you a question when it needs one."),
    "skills" to ("Skills" to "Use the skills the profile has."),
    "vision" to ("Screen pictures" to "Look at pictures, including what's on your screen."),
    ASSISTANT_OPTIONAL_TOOLSET to ("Reminders and scheduled jobs" to "Let it set reminders and jobs that run later. Off by default: a voice request shouldn't schedule unattended work."),
)

/**
 * Setup for the voice assistant: its profile's name and the toolsets it may use. Making it writes the profile on the
 * gateway (the default's model, a spoken-prose SOUL, only these toolsets); the caller records it in Settings.
 */
@Composable
internal fun AssistantSetupSheet(
    /** Profile names already on the gateway, which the new one can't take. */
    taken: Set<String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onMake: (profile: String, toolsets: Set<String>) -> Unit,
) {
    val name = rememberTextFieldState(ASSISTANT_PROFILE)
    val toolsets = remember { mutableStateOf((ASSISTANT_TOOLSETS).toSet()) }
    val problem = BotDraft(profile = name.text.toString().trim()).problem(taken)
    BottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Voice assistant", style = Theme[typography][title], color = Theme[colors][text])
            Text(
                "Makes a bot for the assistant, with a short spoken-style SOUL and the tools below. It uses the same model as a new bot. You can change any of it later in Bots.",
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
            )
            TextField(
                state = name,
                modifier = Modifier.fillMaxWidth(),
                label = "Profile name",
                supportingText = "Letters, digits, - and _.",
                error = problem?.takeIf { name.text.isNotEmpty() },
                enabled = !busy,
            )
            Text("Tools", style = Theme[typography][body], color = Theme[colors][text])
            TOOLSET_LABELS.forEach { (id, label) ->
                val (title, detail) = label
                SwitchRow(
                    title = title,
                    detail = detail,
                    checked = id in toolsets.value,
                    onCheckedChange = { checked ->
                        toolsets.value = if (checked) toolsets.value + id else toolsets.value - id
                    },
                )
            }
            if (error != null) Text(error, style = Theme[typography][bodySmall], color = Theme[colors][text])
            Button(
                text = if (busy) "Making it…" else "Make assistant",
                onClick = { onMake(name.text.toString().trim(), toolsets.value) },
                modifier = Modifier.fillMaxWidth(),
                enabled = problem == null && !busy,
                loading = busy,
            )
        }
    }
}

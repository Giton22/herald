package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.cron.DeliveryTarget
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.Chip
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/**
 * The form for a new or existing scheduled job. The schedule is free text that the gateway parses,
 * so the presets just fill it in; anything Hermes's `/cron` accepts works here too.
 */
@Composable
internal fun JobEditorPage(
    editor: JobEditor,
    prompt: TextFieldState,
    schedule: TextFieldState,
    name: TextFieldState,
    deliveryTargets: List<DeliveryTarget>,
    onSetDeliver: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    // A job may deliver somewhere the target list doesn't name (a specific chat, or `origin`); keep it selectable.
    val targets = if (deliveryTargets.none { it.id == editor.deliver }) {
        deliveryTargets + DeliveryTarget(id = editor.deliver)
    } else {
        deliveryTargets
    }
    Column(Modifier.fillMaxSize()) {
        SubpageHeader(if (editor.isNew) "New job" else "Edit job", onBack = onClose)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextField(
                state = prompt,
                label = "Prompt",
                placeholder = "Summarize my unread email and flag anything urgent",
                supportingText = "What the agent does on each run, as a fresh chat.",
                singleLine = false,
                maxLines = 8,
                enabled = !editor.saving,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    state = schedule,
                    label = "Schedule",
                    placeholder = "every 30m",
                    supportingText = scheduleHint(editor),
                    enabled = !editor.saving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SCHEDULE_PRESETS.forEach { (label, value) ->
                        Chip(label, selected = schedule.text.toString() == value, onClick = { schedule.setTextAndPlaceCursorAtEnd(value) })
                    }
                }
            }
            TextField(
                state = name,
                label = "Name",
                placeholder = "Optional",
                enabled = !editor.saving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DELIVER TO", style = Theme[typography][caption], color = Theme[colors][textTertiary])
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.forEach { target ->
                        Chip(target.name, selected = target.id == editor.deliver, onClick = { onSetDeliver(target.id) })
                    }
                }
                targets.firstOrNull { it.id == editor.deliver && !it.homeTargetSet }?.let {
                    Text(
                        "${it.name} has no home channel set on the gateway, so runs would have nowhere to go.",
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textSecondary],
                    )
                }
            }
            editor.error?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
            Button(
                if (editor.isNew) "Schedule job" else "Save",
                onClick = onSave,
                leadingIcon = Lucide.Check,
                loading = editor.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun scheduleHint(editor: JobEditor): String =
    if (editor.isNew) {
        "Like “every 2h”, “0 9 * * *”, “every monday 9am”, or “in 30m” for once."
    } else {
        "Leave as is to keep the current schedule. One-time jobs start empty."
    }

private val SCHEDULE_PRESETS = listOf(
    "Every 30 min" to "every 30m",
    "Hourly" to "every 1h",
    "Daily 9:00" to "0 9 * * *",
    "Weekdays 9:00" to "0 9 * * 1-5",
    "Mondays 9:00" to "0 9 * * 1",
    "Once, in 1h" to "in 1h",
)

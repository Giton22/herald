package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CornerUpLeft
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mail
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Send
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.cron.DeliveryTarget
import dev.hermeskotlin.core.cron.Routines
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography

/**
 * The form for a new or existing scheduled job. The schedule is free text that the gateway parses,
 * so the presets just fill it in; anything Hermes's `/cron` accepts works here too. For a bot's
 * routine ([routineOf] names the bot) the name is needed, as Bot Mode lists routines by it.
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
    routineOf: String? = null,
) {
    val noun = if (routineOf != null) "routine" else "job"
    // A job may deliver somewhere the target list doesn't name (a specific chat, or `origin`); keep it selectable.
    val targets = if (deliveryTargets.none { it.id == editor.deliver }) {
        deliveryTargets + DeliveryTarget(id = editor.deliver)
    } else {
        deliveryTargets
    }
    Column(Modifier.fillMaxSize()) {
        SubpageHeader(if (editor.isNew) "New $noun" else "Edit $noun", onBack = onClose)
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
                supportingText = routineOf?.let { "What $it does on each run, as itself, with its own memory and skills." }
                    ?: "What the agent does on each run, as a fresh chat.",
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
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SCHEDULE_PRESETS.forEach { (label, value) ->
                        Choice(selected = schedule.text.toString() == value, onClick = { schedule.setTextAndPlaceCursorAtEnd(value) }, shape = CircleShape) { tint, weight ->
                            Text(label, style = Theme[typography][bodySmall].copy(fontSize = 13.sp, fontWeight = weight), color = tint, maxLines = 1)
                        }
                    }
                }
            }
            TextField(
                state = name,
                label = "Name",
                placeholder = if (routineOf != null) "Morning brief" else "Optional",
                enabled = !editor.saving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Deliver to", style = Theme[typography][label], color = Theme[colors][textSecondary], modifier = Modifier.padding(start = 4.dp))
                DeliverTiles(targets, selected = editor.deliver, onSelect = onSetDeliver, botLabel = routineOf)
                targets.firstOrNull { it.id == editor.deliver && !it.homeTargetSet }?.let {
                    Text(
                        "${it.name} has no home channel set on the gateway, so runs would have nowhere to go.",
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textSecondary],
                    )
                }
                if (editor.deliver == Routines.BOT_CHAT_DELIVERY) {
                    Text(
                        "Each run's result arrives in ${routineOf ?: "the bot"}'s chat as a message it reads and answers, so a run costs it one more turn.",
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textSecondary],
                    )
                }
            }
            editor.error?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
            Button(
                if (editor.isNew) "Schedule $noun" else "Save",
                onClick = onSave,
                leadingIcon = Lucide.Check,
                loading = editor.saving,
                size = ButtonSize.Large,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/**
 * Where runs go, as tiles of an icon over the name: as many to a row (up to four) as fit at least 88dp
 * wide at the user's font size. A long name, like one chat's id, gets a row of its own.
 */
@Composable
private fun DeliverTiles(targets: List<DeliveryTarget>, selected: String, onSelect: (String) -> Unit, botLabel: String?) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val labels = targets.associate { it.id to deliveryLabel(it.id, targets, botLabel) }
    val (wide, narrow) = targets.partition { labels.getValue(it.id).length > 14 }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = ((maxWidth + 6.dp) / (88.dp * fontScale + 6.dp)).toInt().coerceIn(1, 4)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (narrow.chunked(perRow) + wide.map { listOf(it) }).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { target ->
                        Choice(
                            selected = target.id == selected,
                            onClick = { onSelect(target.id) },
                            shape = RoundedCornerShape(Theme[radii][radiusMedium]),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            horizontalPadding = 6.dp,
                        ) { tint, weight ->
                            Column(
                                Modifier.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                UnstyledIcon(targetIcon(target.id), contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
                                Text(
                                    labels.getValue(target.id),
                                    style = Theme[typography][caption].copy(fontSize = 12.sp, fontWeight = if (weight == FontWeight.Normal) FontWeight.Medium else weight),
                                    color = tint,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    // Empty slots keep the last row's tiles the same width as the rest; a wide one fills its row.
                    if (row.first() in narrow) repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A choice among several, ringed in the accent when picked. Its content is given the color and weight to
 * draw in: the accent text, semibold, when picked, else the secondary text.
 */
@Composable
private fun Choice(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 13.dp,
    content: @Composable (tint: Color, weight: FontWeight) -> Unit,
) {
    val tint = if (selected) Theme[colors][accentText] else Theme[colors][textSecondary]
    Box(
        modifier
            .heightIn(min = MinTouchTarget)
            .clip(shape)
            .background(if (selected) Theme[colors][accentSoft] else Theme[colors][surface])
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Theme[colors][accentText] else Theme[colors][stroke], shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        content(tint, if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** An icon for where a job's runs go. */
private fun targetIcon(id: String): ImageVector = when (id.lowercase().substringBefore(':')) {
    "local" -> Lucide.HardDrive
    "telegram" -> Lucide.Send
    "email", "mail" -> Lucide.Mail
    "discord" -> Lucide.MessageCircle
    "slack" -> Lucide.Hash
    "origin" -> Lucide.CornerUpLeft
    else -> Lucide.MessageSquare
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

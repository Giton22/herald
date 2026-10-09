package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleDashed
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.core.chat.Subagent
import dev.hermeskotlin.core.chat.SubagentRow
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.subagentRows
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.successSoft
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/** The chat's subagents and a way to stop one, for the delegation cards in its replies. */
internal class SubagentContext(val subagents: List<Subagent>, val onStop: (String) -> Unit)

internal val LocalSubagents = compositionLocalOf { SubagentContext(emptyList()) {} }

/**
 * A `delegate_task` call as the agents it started: one card per task with what it's doing now, and
 * any subagents it started in turn nested beneath. Tap a card for its full activity and result.
 */
@Composable
internal fun DelegationCard(call: ToolActivity) {
    val context = LocalSubagents.current
    val rows = remember(call, context.subagents) { subagentRows(call, context.subagents) }
    if (rows.isEmpty()) return
    // A little more room than the glow's spread would like, so a live card's glow doesn't wash over the next.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            key(row.key) { SubagentCard(row, context.onStop) }
        }
    }
}

/** A surface card; a live one has an accent edge and a soft accent glow, a finished one a hairline. */
@Composable
private fun SubagentCard(row: SubagentRow, onStop: (String) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val live = row.status.live
    val soft = Theme[colors][accentSoft]
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (live) Modifier.dropShadow(shape, Shadow(radius = 20.dp, color = soft)) else Modifier)
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, if (live) soft else Theme[colors][stroke], shape)
            // The end stays put when Stop goes, so the text doesn't shift as the subagent finishes.
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 12.dp),
    ) {
        SubagentRowView(row, onStop)
    }
}

@Composable
private fun SubagentRowView(row: SubagentRow, onStop: (String) -> Unit) {
    var expanded by remember(row.key) { mutableStateOf(false) }
    val live = row.status.live
    val hasMore = row.activity.isNotEmpty() || row.summary != null || row.filesWritten.isNotEmpty() || row.goal.length > 80
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The goal and the lines under it are one target that opens the details; Stop stays its own button.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
                .then(
                    if (hasMore) {
                        Modifier
                            .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide details" else "Show details") { expanded = !expanded }
                            .semantics { stateDescription = if (expanded) "Details shown" else "Details hidden" }
                    } else {
                        Modifier.semantics(mergeDescendants = true) {}
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDisc(row.status)
            Text(
                row.goal,
                style = Theme[typography][bodySmall].copy(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                color = Theme[colors][text],
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            row.subagentId?.let { id -> StopButton { onStop(id) } }
        }
        Column(Modifier.padding(start = 34.dp, end = 8.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Collapsed, one line says where it's at: what it's doing now, or how it ended.
            val glance = if (live) row.thinking ?: row.activity.lastOrNull() else row.summary ?: row.activity.lastOrNull()
            if (!expanded && glance != null) {
                Text(
                    glance,
                    style = Theme[typography][bodySmall].copy(fontSize = 13.5.sp, lineHeight = 19.5.sp),
                    color = when {
                        row.status == SubagentStatus.Failed -> Theme[colors][danger]
                        live -> Theme[colors][accentText]
                        else -> Theme[colors][textSecondary]
                    },
                    maxLines = if (live) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            meta(row).takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = Theme[typography][code].copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = Theme[colors][textMuted],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 34.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.activity.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Activity", style = Theme[typography][caption], color = Theme[colors][textSecondary])
                        CommentableSelection(CommentSource(row.key, "the activity of the subagent working on “${row.goal.take(80)}”", code = true)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                row.activity.forEach { line ->
                                    Text(line, style = Theme[typography][code], color = Theme[colors][textSecondary])
                                }
                            }
                        }
                    }
                }
                row.summary?.let { summary ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (row.status == SubagentStatus.Failed) "What went wrong" else "Result", style = Theme[typography][caption], color = Theme[colors][textSecondary])
                        CommentableSelection(CommentSource(row.key, "the result of the subagent working on “${row.goal.take(80)}”")) {
                            Text(summary, style = Theme[typography][bodySmall], color = if (row.status == SubagentStatus.Failed) Theme[colors][danger] else Theme[colors][text])
                        }
                    }
                }
                if (row.filesWritten.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Files written", style = Theme[typography][caption], color = Theme[colors][textSecondary])
                        row.filesWritten.forEach { Text(it, style = Theme[typography][code], color = Theme[colors][textSecondary]) }
                    }
                }
            }
        }
        if (row.children.isNotEmpty()) {
            val line = Theme[colors][stroke]
            Column(
                Modifier
                    .padding(start = 11.dp, top = 2.dp)
                    // A rule down the left ties the children to the subagent that started them.
                    .drawBehind { drawLine(line, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx()) }
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.children.forEach { child -> key(child.key) { SubagentRowView(child, onStop) } }
            }
        }
    }
}

/** A 24dp disc saying where it stands: a spinner while it runs, a check when done, a cross when it failed. */
@Composable
private fun StatusDisc(status: SubagentStatus) {
    val (fill, tint) = when (status) {
        SubagentStatus.Running -> Theme[colors][accentSoft] to Theme[colors][accentText]
        SubagentStatus.Done -> Theme[colors][successSoft] to Theme[colors][success]
        SubagentStatus.Failed -> Theme[colors][dangerSoft] to Theme[colors][danger]
        else -> Theme[colors][surface3] to Theme[colors][textTertiary]
    }
    val (icon, said) = statusIcon(status)
    Box(
        // Clears the spinner's own "Loading", so the disc says its state once.
        Modifier.size(24.dp).background(fill, CircleShape).clearAndSetSemantics { contentDescription = said },
        contentAlignment = Alignment.Center,
    ) {
        if (icon == null) Spinner(Modifier.size(12.dp), color = tint)
        else UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
    }
}

/** The disc's icon (null for the spinner) and what it says aloud. */
private fun statusIcon(status: SubagentStatus): Pair<ImageVector?, String> = when (status) {
    SubagentStatus.Running -> null to "Running"
    SubagentStatus.Queued -> Lucide.Clock to "Waiting to start"
    SubagentStatus.Done -> Lucide.Check to "Done"
    SubagentStatus.Failed -> Lucide.X to "Failed"
    SubagentStatus.Interrupted -> Lucide.Ban to "Stopped"
    // Not a spinner: nothing here is watching it, so it can't claim to be live.
    SubagentStatus.Unwatched -> Lucide.CircleDashed to "Running in the background"
}

/** A 30dp round stop in a full-size touch target. */
@Composable
private fun StopButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(MinTouchTarget)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Stop this subagent" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Theme[colors][surface3])
                .indication(interaction, rememberColoredIndication(Theme[colors][text])),
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(Lucide.Square, contentDescription = null, tint = Theme[colors][text], modifier = Modifier.size(10.dp))
        }
    }
}

/** "model · 12s · 5 tools", whichever are known. */
private fun meta(row: SubagentRow): String = listOfNotNull(
    row.model?.let(::displayModelName),
    when (row.status) {
        SubagentStatus.Queued -> "Waiting to start"
        SubagentStatus.Interrupted -> "Stopped"
        SubagentStatus.Unwatched -> "In the background"
        else -> null
    },
    row.durationSeconds?.takeIf { !row.status.live }?.let(::formatDuration),
    row.toolCount?.takeIf { it > 0 }?.let { if (it == 1) "1 tool" else "$it tools" },
).joinToString(" · ")

/** The dot color for a subagent in the live card's stack. */
@Composable
internal fun subagentDotColor(status: SubagentStatus): Color = when (status) {
    SubagentStatus.Done -> Theme[colors][success]
    SubagentStatus.Failed -> Theme[colors][danger]
    SubagentStatus.Running, SubagentStatus.Queued -> Theme[colors][accentText]
    else -> Theme[colors][textMuted]
}

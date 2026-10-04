package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleDashed
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Square
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.Subagent
import dev.hermeskotlin.core.chat.SubagentRow
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.subagentRows
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.components.MinTouchTarget

/** The chat's subagents and a way to stop one, for the delegation cards in its replies. */
internal class SubagentContext(val subagents: List<Subagent>, val onStop: (String) -> Unit)

internal val LocalSubagents = compositionLocalOf { SubagentContext(emptyList()) {} }

/**
 * A `delegate_task` call as the agents it started: one row per task with what it's doing now, and
 * any subagents it started in turn nested beneath. Tap a row for its full activity and result.
 */
@Composable
internal fun DelegationCard(call: ToolActivity) {
    val context = LocalSubagents.current
    val rows = remember(call, context.subagents) { subagentRows(call, context.subagents) }
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            key(row.key) {
                val shape = RoundedCornerShape(Theme[radii][radiusMedium])
                Box(Modifier.fillMaxWidth().border(1.dp, Theme[colors][stroke], shape).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    SubagentRowView(row, context.onStop)
                }
            }
        }
    }
}

@Composable
private fun SubagentRowView(row: SubagentRow, onStop: (String) -> Unit) {
    var expanded by remember(row.key) { mutableStateOf(false) }
    val live = row.status.live
    val hasMore = row.activity.isNotEmpty() || row.summary != null || row.filesWritten.isNotEmpty() || row.goal.length > 80
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
                .then(if (hasMore) Modifier.clickable { expanded = !expanded } else Modifier),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 3.dp)) { StatusGlyph(row.status) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.goal,
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][text],
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                meta(row).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Collapsed, one line says where it's at: what it's doing now, or how it ended.
                val glance = if (live) row.thinking ?: row.activity.lastOrNull() else row.summary ?: row.activity.lastOrNull()
                if (!expanded && glance != null) {
                    Text(
                        glance,
                        style = Theme[typography][caption],
                        color = if (row.status == SubagentStatus.Failed) Theme[colors][danger] else Theme[colors][textSecondary],
                        maxLines = if (live) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            row.subagentId?.let { id ->
                UnstyledButton(
                    onClick = { onStop(id) },
                    modifier = Modifier.size(MinTouchTarget).clip(RoundedCornerShape(Theme[radii][radiusSmall])),
                ) {
                    UnstyledIcon(Lucide.Square, contentDescription = "Stop this subagent", tint = Theme[colors][textSecondary], modifier = Modifier.size(12.dp))
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.activity.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Activity", style = Theme[typography][caption], color = Theme[colors][textSecondary])
                        SelectionContainer {
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
                        SelectionContainer {
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
                    .padding(start = 6.dp, top = 2.dp)
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

@Composable
private fun StatusGlyph(status: SubagentStatus) {
    val small = Modifier.size(14.dp)
    when (status) {
        SubagentStatus.Running -> Spinner(small)
        SubagentStatus.Queued -> UnstyledIcon(Lucide.Clock, contentDescription = "Waiting to start", tint = Theme[colors][textTertiary], modifier = small)
        SubagentStatus.Done -> UnstyledIcon(Lucide.Check, contentDescription = "Done", tint = Theme[colors][success], modifier = small)
        SubagentStatus.Failed -> UnstyledIcon(Lucide.CircleX, contentDescription = "Failed", tint = Theme[colors][danger], modifier = small)
        SubagentStatus.Interrupted -> UnstyledIcon(Lucide.Ban, contentDescription = "Stopped", tint = Theme[colors][textTertiary], modifier = small)
        // Not a spinner: nothing here is watching it, so it can't claim to be live.
        SubagentStatus.Unwatched -> UnstyledIcon(Lucide.CircleDashed, contentDescription = "Running in the background", tint = Theme[colors][textTertiary], modifier = small)
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

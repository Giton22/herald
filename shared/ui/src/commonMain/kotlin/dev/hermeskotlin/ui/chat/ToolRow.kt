package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.Wrench
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.ToolRisk
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.CopyButton
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning

/**
 * One tool call in a reply's tool list: its name, time and one-line summary; tap it to see what it
 * was given and what came back, or the edit it made.
 */
@Composable
internal fun ToolRow(tool: ToolActivity) {
    val hasDetails = tool.input != null || tool.output != null || tool.diff != null || tool.risk != null
    var expanded by remember(tool.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
                .then(if (hasDetails) Modifier.clickable { expanded = !expanded } else Modifier),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 3.dp)) {
                when {
                    tool.running -> Spinner(Modifier.size(14.dp))
                    tool.failed -> UnstyledIcon(Lucide.CircleX, contentDescription = "Failed", tint = Theme[colors][danger], modifier = Modifier.size(14.dp))
                    tool.summary != null || tool.durationSeconds != null || tool.output != null ->
                        UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][success], modifier = Modifier.size(14.dp))
                    else -> UnstyledIcon(Lucide.Wrench, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tool.name, style = Theme[typography][label], color = Theme[colors][text])
                    tool.durationSeconds?.let {
                        Text(formatDuration(it), style = Theme[typography][caption], color = Theme[colors][textTertiary], modifier = Modifier.padding(top = 2.dp))
                    }
                    if (tool.risk != null) {
                        Row(
                            Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UnstyledIcon(Lucide.ShieldAlert, contentDescription = null, tint = Theme[colors][warning], modifier = Modifier.size(12.dp))
                            Text("Suspicious output", style = Theme[typography][caption], color = Theme[colors][warning])
                        }
                    }
                }
                val detail = tool.summary ?: tool.detail
                if (!detail.isNullOrBlank() && !expanded) {
                    Text(
                        detail.trim(),
                        style = Theme[typography][caption],
                        color = Theme[colors][textTertiary],
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasDetails) {
                UnstyledIcon(
                    if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = Theme[colors][textTertiary],
                    modifier = Modifier.padding(top = 3.dp).size(14.dp),
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tool.risk?.let { RiskNote(it) }
                tool.input?.let { Block("Input", it) }
                tool.diff?.let { Block("Changes", it, diff = true) }
                tool.output?.let { Block(if (tool.failed) "Error" else "Output", it) }
                if (tool.running && tool.output == null) {
                    Text("Still running…", style = Theme[typography][caption], color = Theme[colors][textTertiary])
                }
            }
        }
    }
}

/** What the scan found in the output. The agent still saw it, fenced off as untrusted. */
@Composable
private fun RiskNote(risk: ToolRisk) {
    val shape = RoundedCornerShape(Theme[radii][radiusSmall])
    val tint = Theme[colors][warning]
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, tint.copy(alpha = 0.4f), shape)
            .background(tint.copy(alpha = 0.08f), shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "This output looks like it's trying to steer the agent or reach secrets. The agent was told not to trust it.",
            style = Theme[typography][caption],
            color = Theme[colors][text],
        )
        risk.labels.takeIf { it.isNotEmpty() }?.let { labels ->
            Text("Found: ${labels.joinToString(", ")}", style = Theme[typography][caption], color = Theme[colors][textSecondary])
        }
        if (risk.redacted) {
            Text("Parts of it were redacted.", style = Theme[typography][caption], color = Theme[colors][textSecondary])
        }
    }
}

/** A titled, scrollable box of monospaced text with a copy button. */
@Composable
private fun Block(title: String, content: String, diff: Boolean = false) {
    val shape = RoundedCornerShape(Theme[radii][radiusSmall])
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Theme[typography][caption], color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
            CopyButton(content)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .border(1.dp, Theme[colors][stroke], shape)
                .background(Theme[colors][background], shape)
                .verticalScroll(rememberScrollState())
                // Long lines scroll sideways rather than wrapping, so output keeps its columns.
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            SelectionContainer {
                BasicText(
                    if (diff) diffText(content) else AnnotatedString(content),
                    style = Theme[typography][code].copy(color = Theme[colors][text]),
                    softWrap = false,
                )
            }
        }
    }
}

/** Added lines in the success colour, removed ones in danger, hunk headers quiet. */
@Composable
private fun diffText(diff: String): AnnotatedString {
    val added = Theme[colors][success]
    val removed = Theme[colors][danger]
    val quiet = Theme[colors][textTertiary]
    return buildAnnotatedString {
        diff.lines().forEachIndexed { i, line ->
            if (i > 0) append('\n')
            val color = when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") -> quiet
                line.startsWith("+") -> added
                line.startsWith("-") -> removed
                else -> null
            }
            if (color != null) withStyle(SpanStyle(color = color)) { append(line) } else append(line)
        }
    }
}

internal fun formatDuration(seconds: Double): String = when {
    seconds < 1 -> "<1s"
    seconds < 60 -> "${seconds.toInt()}s"
    else -> "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s"
}

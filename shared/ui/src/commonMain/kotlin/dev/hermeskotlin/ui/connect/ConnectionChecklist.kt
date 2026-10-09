package dev.hermeskotlin.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.X
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.successSoft
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.designsystem.well
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.CheckStage
import dev.hermeskotlin.core.gateway.StageResult
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/**
 * The connection stages in order, each with its outcome: passed, failed with what to do, skipped, or
 * still to come ([pending], shown as [pendingNote]). The stage being checked shows a spinner.
 */
@Composable
internal fun ConnectionChecklist(
    results: Map<CheckStage, StageResult>,
    running: Boolean,
    modifier: Modifier = Modifier,
    pendingNote: (CheckStage) -> String = { "Not checked yet." },
) {
    val current = if (running) CheckStage.entries.firstOrNull { it !in results } else null
    val stages = CheckStage.entries
    Column(modifier.fillMaxWidth()) {
        stages.forEachIndexed { index, stage ->
            StageRow(
                stage.label,
                results[stage],
                checking = stage == current,
                pendingNote(stage),
                step = "Step ${index + 1} of ${stages.size}",
                first = index == 0,
                last = index == stages.lastIndex,
            )
        }
    }
}

/**
 * One stage on the line: a 28dp disc (a check, a cross, a dash, or a spinner while it runs), its name and what
 * came of it. A failure says what to do in a box with a bulb.
 */
@Composable
private fun StageRow(title: String, result: StageResult?, checking: Boolean, pendingNote: String, step: String, first: Boolean, last: Boolean) {
    val line = Theme[colors][strokeStrong]
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // The line joins the discs: up to this one's top, and on from its bottom to the next.
        Box(
            Modifier
                .width(28.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    val top = DISC_TOP.toPx()
                    val w = 2.dp.toPx()
                    if (!first) drawLine(line, Offset(x, 0f), Offset(x, top), w)
                    if (!last) drawLine(line, Offset(x, top + 28.dp.toPx()), Offset(x, size.height), w)
                },
        ) {
            StageDisc(result, checking, step, Modifier.padding(top = DISC_TOP))
        }
        Column(Modifier.weight(1f).padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text])
            val detail = when {
                checking -> "Checking…"
                result is StageResult.Passed -> result.detail
                result is StageResult.Failed -> result.problem
                result is StageResult.Skipped -> result.reason
                else -> pendingNote
            }
            Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
            if (result is StageResult.Failed) FixHint(result.fix)
        }
    }
}

@Composable
private fun StageDisc(result: StageResult?, checking: Boolean, step: String, modifier: Modifier) {
    val (fill, tint, icon, spoken) = when {
        checking -> Quad(Theme[colors][surface2], Theme[colors][accentText], null, "Checking")
        result is StageResult.Passed -> Quad(Theme[colors][successSoft], Theme[colors][success], Lucide.Check, "Passed")
        result is StageResult.Failed -> Quad(Theme[colors][dangerSoft], Theme[colors][danger], Lucide.X, "Failed")
        result is StageResult.Skipped -> Quad(Theme[colors][surface2], Theme[colors][textTertiary], Lucide.Minus, "Skipped")
        else -> Quad(Theme[colors][surface2], Theme[colors][textTertiary], null, "Not checked")
    }
    Box(
        // One description for the disc, the spinner's own "Loading" folded in.
        modifier.size(28.dp).background(fill, CircleShape).clearAndSetSemantics { contentDescription = "$step, $spoken" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            checking -> Spinner(Modifier.size(14.dp), color = tint)
            icon != null -> UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            // Still to come: an empty ring.
            else -> Box(Modifier.size(10.dp).border(1.5.dp, tint, CircleShape))
        }
    }
}

/** How a stage's disc looks, and what it says. */
private data class Quad(val fill: Color, val tint: Color, val icon: ImageVector?, val spoken: String)

/** What to do about a failed stage, on a recessed box with a bulb. */
@Composable
private fun FixHint(fix: String) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .background(Theme[colors][well], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UnstyledIcon(Lucide.Lightbulb, contentDescription = "What to do", tint = Theme[colors][warning], modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Text(fix, style = Theme[typography][bodySmall].copy(fontSize = 13.sp), color = Theme[colors][textSecondary])
    }
}

private val DISC_TOP = 6.dp

/** Which address to enter, for the three usual ways of reaching a dashboard. */
@Composable
internal fun AddressGuide(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GuideItem(
            "Same Wi-Fi or local network",
            "Use the server's local address and port, like 192.168.1.20:9119. Start the dashboard with --host 0.0.0.0 so other devices can reach it.",
        )
        GuideItem(
            "Tailscale",
            "Turn on Tailscale on this phone and use the server's Tailscale address, like 100.64.0.1:9119 or its .ts.net name. Works away from home too.",
        )
        GuideItem(
            "HTTPS",
            "Use your https:// address when the dashboard sits behind a proxy with a certificate. The proxy must also pass WebSocket upgrades on /api/ws.",
        )
    }
}

@Composable
private fun GuideItem(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Theme[typography][bodySmall], color = Theme[colors][text])
        Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
    }
}

package dev.hermeskotlin.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleDashed
import com.composables.icons.lucide.CircleMinus
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.Lucide
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CheckStage.entries.forEachIndexed { index, stage ->
            StageRow("${index + 1}. ${stage.label}", results[stage], checking = stage == current, pendingNote(stage))
        }
    }
}

@Composable
private fun StageRow(title: String, result: StageResult?, checking: Boolean, pendingNote: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        val iconModifier = Modifier.padding(top = 2.dp).size(18.dp)
        when {
            checking -> Spinner(iconModifier)
            result is StageResult.Passed -> UnstyledIcon(Lucide.CircleCheck, contentDescription = "Passed", tint = Theme[colors][success], modifier = iconModifier)
            result is StageResult.Failed -> UnstyledIcon(Lucide.CircleX, contentDescription = "Failed", tint = Theme[colors][danger], modifier = iconModifier)
            result is StageResult.Skipped -> UnstyledIcon(Lucide.CircleMinus, contentDescription = "Skipped", tint = Theme[colors][textTertiary], modifier = iconModifier)
            else -> UnstyledIcon(Lucide.CircleDashed, contentDescription = "Not checked", tint = Theme[colors][textTertiary], modifier = iconModifier)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme[typography][body], color = Theme[colors][text])
            val detail = when {
                checking -> "Checking…"
                result is StageResult.Passed -> result.detail
                result is StageResult.Failed -> result.problem
                result is StageResult.Skipped -> result.reason
                else -> pendingNote
            }
            Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
            if (result is StageResult.Failed) {
                Text("What to do: ${result.fix}", style = Theme[typography][bodySmall], color = Theme[colors][text])
            }
        }
    }
}

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

package dev.hermeskotlin.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.widget.ChatsWidgetSnapshot
import dev.hermeskotlin.core.widget.KanbanStaleTier
import dev.hermeskotlin.core.widget.KanbanWidgetSnapshot
import dev.hermeskotlin.core.widget.NowNeedsYouItem
import dev.hermeskotlin.core.widget.NowRunningItem
import dev.hermeskotlin.core.widget.NowRunningKind
import dev.hermeskotlin.core.widget.NowWidgetSnapshot
import dev.hermeskotlin.core.widget.ScheduledWidgetSnapshot
import dev.hermeskotlin.designsystem.body as bodyStyle
import dev.hermeskotlin.designsystem.caption as captionStyle
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title as titleStyle
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.ui.components.localMoment

/**
 * The Compose twin of the Android widgets' `RemoteViews` renderers (D28, `docs/WIDGETS-SPEC.md`): the same
 * snapshots, the same rows, drawn here for the preview gallery and the drift-guard tests. The locked
 * variant (D16) shows counts only — no titles, names, times or faces.
 */
enum class NowPreviewState { NeedsYou, Running, Quiet, Empty, SignedOut, OfflineSaved }

private const val LOCKED_LINE = "Locked. Unlock to see details."

private fun epochToHHMM(epochSeconds: Long?): String {
    val seconds = epochSeconds ?: return ""
    val at = localMoment(seconds * 1000)
    return "%02d:%02d".format(at.hour, at.minute)
}

/** The chrome every widget shares: the header with the widget's name, the body column inside one frame. */
@Composable
private fun WidgetFrame(name: String, asOfEpochSeconds: Long? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = Theme[typography][titleStyle], color = Theme[colors][text])
            val asOf = asOfEpochSeconds
            if (asOf != null) Text("as of ${epochToHHMM(asOf)}", style = Theme[typography][captionStyle], color = Theme[colors][textTertiary])
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

/** One content row: the title and, when there is one, the detail line beneath it. */
@Composable
private fun WidgetRow(title: String, detail: String? = null, titleColor: Color = Theme[colors][text]) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = Theme[typography][bodyStyle], color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) Text(detail, style = Theme[typography][captionStyle], color = Theme[colors][textSecondary], maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NowWidgetPreview(
    snapshot: NowWidgetSnapshot,
    state: NowPreviewState = NowPreviewState.NeedsYou,
    locked: Boolean = false,
) {
    WidgetFrame("Now", snapshot.socketSavedAtEpochSeconds) {
        when {
            locked -> Text("Needs you: ${snapshot.needsYou.size} · Running: ${snapshot.running.size}", style = Theme[typography][bodyStyle], color = Theme[colors][textSecondary])
            state == NowPreviewState.SignedOut -> Text("Sign in to see what needs you.", style = Theme[typography][bodyStyle], color = Theme[colors][textSecondary])
            state == NowPreviewState.OfflineSaved -> Text("Saved copy · as of ${epochToHHMM(snapshot.savedAtEpochSeconds)}", style = Theme[typography][captionStyle], color = Theme[colors][textTertiary])
            state == NowPreviewState.Empty -> Text("Nothing needs you and nothing is running.", style = Theme[typography][bodyStyle], color = Theme[colors][textSecondary])
            else -> {
                snapshot.needsYou.forEach { item: NowNeedsYouItem ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(18.dp).background(Theme[colors][danger], RoundedCornerShape(2.dp)))
                        WidgetRow(item.title, item.detail)
                    }
                }
                snapshot.running.forEach { item: NowRunningItem ->
                    WidgetRow(item.title, when (item.kind) {
                        NowRunningKind.ChatTurn -> "Working…"
                        NowRunningKind.BotWorking -> "Working in the background"
                        NowRunningKind.KanbanRunning -> "Running"
                    })
                }
                val quiet = snapshot.quiet
                if (snapshot.needsYou.isEmpty() && snapshot.running.isEmpty() && quiet != null) {
                    Text(
                        quiet.nextJobName?.let { "Quiet. Next up: $it at ${epochToHHMM(quiet.nextRunEpochSeconds)}." }
                            ?: "Quiet. Nothing is scheduled.",
                        style = Theme[typography][bodyStyle], color = Theme[colors][textSecondary],
                    )
                    // Quiet's quick actions (D19): the same New chat / Dictate pair the widget shows.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("New chat", style = Theme[typography][bodyStyle], color = Theme[colors][accent])
                        Text("Dictate", style = Theme[typography][bodyStyle], color = Theme[colors][accent])
                    }
                }
            }
        }
    }
}

@Composable
fun ChatsWidgetPreview(snapshot: ChatsWidgetSnapshot, locked: Boolean = false) {
    WidgetFrame("Chats", snapshot.savedAtEpochSeconds.takeIf { snapshot.rows.any { it.savedCopy } }) {
        if (locked) {
            Text(LOCKED_LINE, style = Theme[typography][bodyStyle], color = Theme[colors][textSecondary])
        } else {
            snapshot.rows.forEach { row ->
                WidgetRow(
                    title = row.title + if (row.unread) " ●" else "",
                    detail = row.waitingLabel ?: row.subtitle,
                )
            }
            if (snapshot.rows.isEmpty()) Text("No chats yet.", style = Theme[typography][bodyStyle], color = Theme[colors][textSecondary])
        }
    }
}

@Composable
fun KanbanWidgetPreview(snapshot: KanbanWidgetSnapshot, locked: Boolean = false) {
    WidgetFrame(snapshot.boardName) {
        if (locked) {
            Text(LOCKED_LINE, style = Theme[typography][bodyStyle], color = Theme[colors][textSecondary])
        } else {
            // The 4-pill count strip (§8.2): running, blocked, review, ready — always all four pills.
            val counts = snapshot.counts
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                @Composable
                fun pill(label: String, n: Int, color: Color) {
                    Row(
                        Modifier.border(1.dp, color, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(n.toString(), style = Theme[typography][captionStyle], color = color)
                        Text(label, style = Theme[typography][captionStyle], color = Theme[colors][textSecondary])
                    }
                }
                pill("running", counts["running"] ?: 0, Theme[colors][accent])
                pill("blocked", counts["blocked"] ?: 0, Theme[colors][danger])
                pill("review", counts["review"] ?: 0, Theme[colors][warning])
                pill("ready", counts["ready"] ?: 0, Theme[colors][success])
            }
            snapshot.rows.forEach { row ->
                WidgetRow(
                    title = row.title + if (row.warningCount > 0) " ⚠" else "",
                    detail = row.status + (row.assignee?.let { " · $it" } ?: ""),
                    titleColor = when (row.stale) {
                        KanbanStaleTier.Red -> Theme[colors][danger]
                        KanbanStaleTier.Amber -> Theme[colors][warning]
                        KanbanStaleTier.None -> Theme[colors][text]
                    },
                )
            }
            if (snapshot.rows.isEmpty()) Text("No tasks on this board.", style = Theme[typography][bodyStyle], color = Theme[colors][textSecondary])
        }
    }
}

@Composable
fun ScheduledWidgetPreview(snapshot: ScheduledWidgetSnapshot, locked: Boolean = false) {
    WidgetFrame("Scheduled") {
        if (locked) {
            Text(LOCKED_LINE, style = Theme[typography][bodyStyle], color = Theme[colors][textSecondary])
        } else {
            snapshot.rows.forEach { row ->
                WidgetRow(
                    title = row.routineOwnerLabel?.let { "$it · ${row.displayName}" } ?: row.displayName,
                    detail = row.problemSummary
                        ?: row.scheduleDisplay
                        ?: row.nextRunEpochSeconds?.let { "Next run ${epochToHHMM(it)}" },
                    titleColor = if (row.isProblem) Theme[colors][danger] else Theme[colors][text],
                )
            }
            if (snapshot.rows.isEmpty()) Text("Nothing scheduled.", style = Theme[typography][bodyStyle], color = Theme[colors][textSecondary])
        }
    }
}

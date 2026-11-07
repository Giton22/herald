package dev.hermeskotlin.ui.connect

import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.components.clientCertificatesAvailable
import dev.hermeskotlin.ui.CopyableOnDesktop
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
import dev.hermeskotlin.core.gateway.CheckNote
import dev.hermeskotlin.core.gateway.CheckStage
import dev.hermeskotlin.core.gateway.StageResult
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource
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
    pendingNote: @Composable (CheckStage) -> String = { stringResource(Res.string.check_not_checked_yet) },
) {
    val current = if (running) CheckStage.entries.firstOrNull { it !in results } else null
    val stages = CheckStage.entries
    Column(modifier.fillMaxWidth()) {
        stages.forEachIndexed { index, stage ->
            StageRow(
                stageTitle(stage),
                results[stage],
                checking = stage == current,
                pendingNote(stage),
                step = stringResource(Res.string.check_step, index + 1, stages.size),
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
                checking -> stringResource(Res.string.common_checking)
                result is StageResult.Passed -> checkNoteText(result.detail)
                result is StageResult.Failed -> checkNoteText(result.problem)
                result is StageResult.Skipped -> checkNoteText(result.reason)
                else -> pendingNote
            }
            // What went wrong and what to do can be selected and copied on a desktop, for a bug report.
            CopyableOnDesktop { Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary]) }
            if (result is StageResult.Failed) FixHint(checkNoteText(result.fix))
        }
    }
}

@Composable
private fun StageDisc(result: StageResult?, checking: Boolean, step: String, modifier: Modifier) {
    val (fill, tint, icon, spoken) = when {
        checking -> Quad(Theme[colors][surface2], Theme[colors][accentText], null, stringResource(Res.string.check_state_checking))
        result is StageResult.Passed -> Quad(Theme[colors][successSoft], Theme[colors][success], Lucide.Check, stringResource(Res.string.check_state_passed))
        result is StageResult.Failed -> Quad(Theme[colors][dangerSoft], Theme[colors][danger], Lucide.X, stringResource(Res.string.check_state_failed))
        result is StageResult.Skipped -> Quad(Theme[colors][surface2], Theme[colors][textTertiary], Lucide.Minus, stringResource(Res.string.check_state_skipped))
        else -> Quad(Theme[colors][surface2], Theme[colors][textTertiary], null, stringResource(Res.string.check_state_not_checked))
    }
    val description = stringResource(Res.string.check_disc, step, spoken)
    Box(
        // One description for the disc, the spinner's own "Loading" folded in.
        modifier.size(28.dp).background(fill, CircleShape).clearAndSetSemantics { contentDescription = description },
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
        UnstyledIcon(Lucide.Lightbulb, contentDescription = stringResource(Res.string.check_what_to_do), tint = Theme[colors][warning], modifier = Modifier.padding(top = 2.dp).size(14.dp))
        CopyableOnDesktop { Text(fix, style = Theme[typography][bodySmall].copy(fontSize = 13.sp), color = Theme[colors][textSecondary]) }
    }
}

private val DISC_TOP = 6.dp

/** Which address to enter, for the three usual ways of reaching a dashboard. */
@Composable
internal fun AddressGuide(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GuideItem(stringResource(Res.string.connect_guide_local), stringResource(Res.string.connect_guide_local_detail))
        GuideItem(
            "Tailscale",
            stringResource(if (isDesktop) Res.string.connect_guide_tailscale_detail_desktop else Res.string.connect_guide_tailscale_detail),
        )
        GuideItem("HTTPS", stringResource(Res.string.connect_guide_https_detail))
    }
}

/** A stage's name. */
@Composable
internal fun stageTitle(stage: CheckStage): String = stringResource(
    when (stage) {
        CheckStage.Server -> Res.string.check_stage_server
        CheckStage.SignIn -> Res.string.check_stage_sign_in
        CheckStage.Live -> Res.string.check_stage_live
    },
)

/** A line of the check in the app's language, its [CheckNote.args] filled in as they came. */
@Composable
internal fun checkNoteText(note: CheckNote): String {
    val res = when (note.kind) {
        CheckNote.Kind.ServerAnswers -> Res.string.check_server_answers
        CheckNote.Kind.SignedInAs -> Res.string.check_signed_in_as
        CheckNote.Kind.LiveReady -> Res.string.check_live_ready
        CheckNote.Kind.NeedsServer -> Res.string.check_needs_server
        CheckNote.Kind.NeedsSignIn -> Res.string.check_needs_sign_in
        CheckNote.Kind.SignInExpired -> Res.string.check_sign_in_expired
        CheckNote.Kind.SignInRateLimited -> Res.string.check_sign_in_rate_limited
        CheckNote.Kind.SignInUnavailable -> Res.string.check_sign_in_unavailable
        CheckNote.Kind.SignInRefused -> Res.string.check_sign_in_refused
        CheckNote.Kind.TicketExpired -> Res.string.check_ticket_expired
        CheckNote.Kind.TicketUnavailable -> Res.string.check_ticket_unavailable
        CheckNote.Kind.RequestsRateLimited -> Res.string.check_requests_rate_limited
        CheckNote.Kind.TicketRefused -> Res.string.check_ticket_refused
        CheckNote.Kind.NeverReady -> Res.string.check_never_ready
        CheckNote.Kind.NoChatEndpoint -> Res.string.check_no_chat_endpoint
        CheckNote.Kind.ChatGuarded -> Res.string.check_chat_guarded
        CheckNote.Kind.ChatLoginRedirect -> Res.string.check_chat_login_redirect
        CheckNote.Kind.ChatRefused -> Res.string.check_chat_refused
        CheckNote.Kind.TicketRejected -> Res.string.check_ticket_rejected
        CheckNote.Kind.ChatCertRequired -> Res.string.check_chat_cert_required
        CheckNote.Kind.ChatFailed -> Res.string.check_chat_failed
        CheckNote.Kind.ServerCertRequired -> Res.string.check_server_cert_required
        CheckNote.Kind.NotHermes -> Res.string.check_not_hermes
        CheckNote.Kind.NotHermesStatus -> Res.string.check_not_hermes_status
        CheckNote.Kind.AccessTokenRefused -> Res.string.check_access_token_refused
        CheckNote.Kind.AccessBlocked -> Res.string.check_access_blocked
        CheckNote.Kind.Unreachable -> Res.string.check_unreachable
        CheckNote.Kind.FixSignInAgain -> Res.string.check_fix_sign_in_again
        CheckNote.Kind.FixSignInPasswordOrBrowser -> Res.string.check_fix_sign_in_password_or_browser
        CheckNote.Kind.FixWaitAMinute -> Res.string.check_fix_wait_a_minute
        CheckNote.Kind.FixSignInOrUpdate -> Res.string.check_fix_sign_in_or_update
        CheckNote.Kind.FixUpdateHermesTryAgain -> Res.string.check_fix_update_hermes_try_again
        CheckNote.Kind.FixUpdateHermes -> Res.string.check_fix_update_hermes
        CheckNote.Kind.FixAgentRunning -> Res.string.check_fix_agent_running
        CheckNote.Kind.FixExactAddress -> Res.string.check_fix_exact_address
        CheckNote.Kind.FixAccessTokenOrProxy -> Res.string.check_fix_access_token_or_proxy
        CheckNote.Kind.FixAllowUpgrades -> Res.string.check_fix_allow_upgrades
        CheckNote.Kind.FixAllowUpgradesOrRetry -> Res.string.check_fix_allow_upgrades_or_retry
        // Android picks a system-store alias; the Linux Flatpak imports a PKCS#12 file; other desktops have neither.
        CheckNote.Kind.FixClientCert -> when {
            !isDesktop -> Res.string.check_fix_client_cert
            clientCertificatesAvailable() -> Res.string.check_fix_client_cert_desktop
            else -> Res.string.check_fix_client_cert_unsupported
        }
        CheckNote.Kind.FixDashboardPort -> Res.string.check_fix_dashboard_port
        CheckNote.Kind.FixCheckAccessToken -> Res.string.check_fix_check_access_token
        CheckNote.Kind.FixAddAccessToken -> Res.string.check_fix_add_access_token
        CheckNote.Kind.FixLoopback -> Res.string.check_fix_loopback
        CheckNote.Kind.FixTailscale -> Res.string.check_fix_tailscale
        CheckNote.Kind.FixHttps -> Res.string.check_fix_https
        CheckNote.Kind.FixLocalNetwork -> Res.string.check_fix_local_network
        CheckNote.Kind.FixReachable -> Res.string.check_fix_reachable
    }
    return stringResource(res, *note.args.toTypedArray())
}

@Composable
private fun GuideItem(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Theme[typography][bodySmall], color = Theme[colors][text])
        Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
    }
}

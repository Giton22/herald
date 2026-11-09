package dev.hermeskotlin.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManagedUpdateBanner(offer: ManagedUpdateOffer, modifier: Modifier = Modifier) {
    val state = offer.state
    if (state.phase == ManagedUpdatePhase.Idle) return
    var confirming by remember { mutableStateOf(false) }
    val busy = state.phase in listOf(ManagedUpdatePhase.Updating, ManagedUpdatePhase.Restarting)
    val message = when (state.phase) {
        ManagedUpdatePhase.Available -> stringResource(Res.string.managed_update_available)
        ManagedUpdatePhase.Ready -> stringResource(Res.string.managed_update_ready)
        ManagedUpdatePhase.NoUpdate -> stringResource(Res.string.managed_update_empty)
        ManagedUpdatePhase.Cancelled -> stringResource(Res.string.managed_update_cancelled)
        ManagedUpdatePhase.Updating -> state.progress?.let { stringResource(Res.string.managed_update_progress, it) }
            ?: stringResource(Res.string.managed_update_working)
        ManagedUpdatePhase.Failed -> stringResource(when (state.failure) {
            ManagedUpdateFailure.Denied -> Res.string.managed_update_denied
            ManagedUpdateFailure.SystemUpdateRequired -> Res.string.managed_update_system
            ManagedUpdateFailure.Uncertain -> Res.string.managed_update_uncertain
            ManagedUpdateFailure.RestartFailed -> Res.string.managed_update_restart_failed
            ManagedUpdateFailure.Unavailable -> Res.string.managed_update_unavailable
            else -> Res.string.managed_update_failed
        })
        else -> stringResource(Res.string.managed_update_working)
    }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val tint = Theme[colors][accent]
    Column(modifier.fillMaxWidth().border(1.dp, tint.copy(alpha = 0.4f), shape)
        .background(tint.copy(alpha = 0.08f), shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = Theme[typography][bodySmall], color = Theme[colors][text])
        if (state.canRestart && state.phase == ManagedUpdatePhase.Available) {
            Text(stringResource(Res.string.managed_update_ready), style = Theme[typography][bodySmall], color = Theme[colors][text])
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.phase == ManagedUpdatePhase.Available || state.phase == ManagedUpdatePhase.Cancelled ||
                state.phase == ManagedUpdatePhase.Failed && state.failure in listOf(ManagedUpdateFailure.Denied, ManagedUpdateFailure.Failed)) {
                Button(stringResource(Res.string.common_update), offer.onUpdate, size = ButtonSize.Small)
            }
            if (state.phase == ManagedUpdatePhase.Updating) {
                Button(stringResource(Res.string.common_cancel), offer.onCancel, size = ButtonSize.Small, variant = ButtonVariant.Ghost)
            }
            if (state.canRestart && !busy && state.failure != ManagedUpdateFailure.RestartFailed) {
                Button(stringResource(Res.string.managed_update_restart), { confirming = true }, size = ButtonSize.Small)
            }
            if (!busy) Button(stringResource(Res.string.common_not_now), offer.onDismiss, size = ButtonSize.Small, variant = ButtonVariant.Ghost)
        }
    }
    Dialog(visible = confirming, onDismissRequest = { confirming = false },
        title = stringResource(Res.string.managed_update_restart_title),
        message = stringResource(Res.string.managed_update_restart_warning), actions = {
            Button(stringResource(Res.string.common_cancel), { confirming = false }, size = ButtonSize.Small, variant = ButtonVariant.Ghost)
            Button(stringResource(Res.string.managed_update_restart_now), { confirming = false; offer.onRestart() }, size = ButtonSize.Small)
        })
}

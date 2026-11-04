package dev.hermeskotlin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Status
import dev.hermeskotlin.designsystem.components.StatusDot
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.CopyableOnDesktop
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlinx.coroutines.delay

/** Live-connection indicator. [detailed] adds the failure reason under the dot. */
@Composable
fun ConnectionLine(state: ConnectionState, detailed: Boolean = false) {
    val reason = when (state) {
        is ConnectionState.Reconnecting -> state.reason
        is ConnectionState.Failed -> state.reason
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            ConnectionState.Idle -> StatusDot(Status.Neutral, stringResource(Res.string.app_not_connected))
            is ConnectionState.Connecting ->
                StatusDot(Status.Warning, stringResource(if (state.attempt > 1) Res.string.app_reconnecting else Res.string.voice_connecting))
            is ConnectionState.Connected -> StatusDot(Status.Ok, stringResource(Res.string.app_connected))
            is ConnectionState.Reconnecting -> {
                val seconds by produceState(secondsUntil(state.retryAtMillis), state.retryAtMillis) {
                    while (true) {
                        value = secondsUntil(state.retryAtMillis)
                        delay(500)
                    }
                }
                StatusDot(Status.Warning, stringResource(Res.string.app_offline_retrying, seconds.toInt()))
            }
            ConnectionState.SessionExpired -> StatusDot(Status.Error, stringResource(Res.string.app_session_expired))
            is ConnectionState.Failed -> StatusDot(Status.Error, stringResource(Res.string.app_connection_refused))
        }
        if (detailed && reason != null) {
            CopyableOnDesktop { Text(reason, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary]) }
        }
    }
}

private fun secondsUntil(epochMillis: Long) =
    ((epochMillis - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0) + 999) / 1000

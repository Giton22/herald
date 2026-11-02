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
import dev.hermeskotlin.ui.i18n.Strings
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
            ConnectionState.Idle -> StatusDot(Status.Neutral, Strings.t("Not connected"))
            is ConnectionState.Connecting -> StatusDot(Status.Warning, if (state.attempt > 1) Strings.t("Reconnecting…") else Strings.t("Connecting…"))
            is ConnectionState.Connected -> StatusDot(Status.Ok, Strings.t("Connected"))
            is ConnectionState.Reconnecting -> {
                val seconds by produceState(secondsUntil(state.retryAtMillis), state.retryAtMillis) {
                    while (true) {
                        value = secondsUntil(state.retryAtMillis)
                        delay(500)
                    }
                }
                StatusDot(Status.Warning, Strings.t("Offline · retrying in {0}s", seconds))
            }
            ConnectionState.SessionExpired -> StatusDot(Status.Error, Strings.t("Session expired"))
            is ConnectionState.Failed -> StatusDot(Status.Error, Strings.t("Connection refused"))
        }
        if (detailed && reason != null) {
            CopyableOnDesktop { Text(reason, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary]) }
        }
    }
}

private fun secondsUntil(epochMillis: Long) =
    ((epochMillis - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0) + 999) / 1000

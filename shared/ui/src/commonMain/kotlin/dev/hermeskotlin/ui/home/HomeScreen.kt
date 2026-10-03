package dev.hermeskotlin.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Server
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Status
import dev.hermeskotlin.designsystem.components.StatusDot
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.ScreenHeader
import dev.hermeskotlin.ui.components.ScreenScaffold
import kotlin.time.Clock
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeScreen(
    gateway: SavedGateway,
    onSignOut: () -> Unit,
    onChangeGateway: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    LaunchedEffect(gateway) { viewModel.load(gateway) }

    ScreenScaffold {
        ScreenHeader(
            icon = Lucide.Server,
            title = gateway.gatewayUrl.host.substringBefore('.'),
            subtitle = "Sessions and chat arrive next. For now this shows the live gateway connection.",
        )

        Surface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionStatus(connection)
                InfoRow("Gateway", gateway.url)
                user?.let { InfoRow("Signed in as", it.label) }
                (connection as? ConnectionState.Connected)?.ready?.get("replay_epoch")?.let {
                    InfoRow("Backend epoch", it.toString().trim('"').take(12))
                }
            }
        }

        if (connection is ConnectionState.Reconnecting || connection is ConnectionState.Failed) {
            Button(
                text = "Retry now",
                onClick = viewModel::retry,
                variant = ButtonVariant.Secondary,
                leadingIcon = Lucide.RefreshCw,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                text = "Sign out",
                onClick = onSignOut,
                variant = ButtonVariant.Outline,
                leadingIcon = Lucide.LogOut,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                text = "Use a different gateway",
                onClick = onChangeGateway,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConnectionStatus(state: ConnectionState) {
    when (state) {
        ConnectionState.Idle -> StatusDot(Status.Neutral, "Not connected")
        is ConnectionState.Connecting -> StatusDot(Status.Warning, if (state.attempt > 1) "Reconnecting…" else "Connecting…")
        is ConnectionState.Connected -> StatusDot(Status.Ok, "Connected")
        is ConnectionState.Reconnecting -> {
            val seconds by produceState(secondsUntil(state.retryAtMillis), state.retryAtMillis) {
                while (true) {
                    value = secondsUntil(state.retryAtMillis)
                    delay(500)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusDot(Status.Warning, "Retrying in ${seconds}s")
                Text(state.reason, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
            }
        }
        ConnectionState.SessionExpired -> StatusDot(Status.Error, "Session expired")
        is ConnectionState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusDot(Status.Error, "Connection refused")
            Text(state.reason, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
        }
    }
}

private fun secondsUntil(epochMillis: Long) = ((epochMillis - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0) + 999) / 1000

@Composable
private fun InfoRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        Text(value, style = Theme[typography][bodySmall], color = Theme[colors][text], modifier = Modifier.padding(start = 16.dp))
    }
}

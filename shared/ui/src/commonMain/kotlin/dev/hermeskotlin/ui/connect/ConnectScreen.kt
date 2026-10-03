package dev.hermeskotlin.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PlugZap
import com.composables.icons.lucide.Server
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.ProbeResult
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Status
import dev.hermeskotlin.designsystem.components.StatusDot
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.ScreenHeader
import dev.hermeskotlin.ui.components.ScreenScaffold
import kotlinx.coroutines.flow.drop
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ConnectScreen(
    onContinue: (SavedGateway) -> Unit,
    viewModel: ConnectViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.url.text.toString() }.drop(1).collect { viewModel.onUrlEdited() }
    }

    ScreenScaffold {
        ScreenHeader(
            icon = Lucide.Server,
            title = "Connect to Hermes",
            subtitle = "Point the app at a remote Hermes gateway. Nothing is sent until you sign in.",
        )

        TextField(
            state = viewModel.url,
            label = "Gateway address",
            placeholder = "100.64.0.1:9119",
            supportingText = "Where your hermes dashboard runs: LAN, Tailscale or HTTPS.",
            error = state.urlError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            onKeyboardAction = { viewModel.testConnection() },
        )

        val result = state.result
        val signInReady = result is ProbeResult.Reachable && result.status.authRequired && result.status.supportsPasswordLogin
        Button(
            text = if (state.testing) "Testing…" else "Test connection",
            onClick = viewModel::testConnection,
            loading = state.testing,
            leadingIcon = Lucide.PlugZap,
            variant = if (signInReady) ButtonVariant.Secondary else ButtonVariant.Primary,
            size = ButtonSize.Large,
            modifier = Modifier.fillMaxWidth(),
        )

        result?.let { ResultCard(it) }

        if (result is ProbeResult.Reachable && result.url.isExposed) {
            Text(
                "This address isn't private and doesn't use https://, so your password and chats would cross " +
                    "the internet unencrypted. Use https://, Tailscale or your own network instead.",
                style = Theme[typography][bodySmall],
                color = Theme[colors][danger],
            )
        }

        if (signInReady) {
            result as ProbeResult.Reachable
            Button(
                text = "Continue to sign in",
                onClick = { onContinue(SavedGateway(result.url.value, provider = PASSWORD_PROVIDER)) },
                leadingIcon = Lucide.ArrowRight,
                size = ButtonSize.Large,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The bundled username/password provider (`auth_providers` entry). */
private const val PASSWORD_PROVIDER = "basic"

@Composable
private fun ResultCard(result: ProbeResult) {
    Surface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (result) {
                is ProbeResult.Reachable -> ReachableContent(result)
                is ProbeResult.NotHermes -> {
                    StatusDot(Status.Error, "Not a Hermes dashboard")
                    Hint(
                        buildString {
                            append("Something answered at ${result.url}")
                            result.httpStatus?.let { append(" (HTTP $it)") }
                            append(", but not a Hermes dashboard. The dashboard listens on port 9119 by default.")
                        },
                    )
                }
                is ProbeResult.Unreachable -> {
                    StatusDot(Status.Error, "Can't reach ${result.url}")
                    Hint(result.reason)
                    Hint("On the server, run:")
                    CodeLine("hermes dashboard --host 0.0.0.0 --port 9119 --no-open")
                }
            }
        }
    }
}

@Composable
private fun ReachableContent(result: ProbeResult.Reachable) {
    val status = result.status
    StatusDot(Status.Ok, "Hermes ${status.version} is reachable")

    InfoRow("Agent gateway", if (status.gatewayRunning) "Running" else "Stopped")
    InfoRow(
        "Sign-in",
        when {
            !status.authRequired -> "Not required"
            status.supportsPasswordLogin -> "Username & password"
            else -> "OAuth only"
        },
    )
    if (status.profiles.isNotEmpty()) InfoRow("Profiles", status.profiles.joinToString())

    if (!result.canSignIn) {
        StatusDot(Status.Warning, "No password provider configured")
        Hint("This dashboard only offers OAuth sign-in, which the app doesn't support yet. Configure the username/password provider on the server.")
    } else if (!status.authRequired) {
        StatusDot(Status.Warning, "Auth gate is off")
        Hint("The dashboard is bound to loopback, so other devices won't be allowed to chat. Bind it with --host 0.0.0.0 and configure a password provider.")
    }
}

@Composable
private fun InfoRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        Text(value, style = Theme[typography][bodySmall], color = Theme[colors][text])
    }
}

@Composable
private fun Hint(message: String) {
    Text(message, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
}

@Composable
private fun CodeLine(command: String) {
    Text(command, style = Theme[typography][code], color = Theme[colors][text])
}

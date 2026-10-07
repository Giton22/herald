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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PlugZap
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.CheckStage
import dev.hermeskotlin.core.gateway.ProbeResult
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.gateway.serverOnlyResults
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.HeraldMark
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
    /** Back to the gateway in use, when one is saved (adding another). */
    onCancel: (() -> Unit)? = null,
    viewModel: ConnectViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.url.text.toString() }.drop(1).collect { viewModel.onUrlEdited() }
    }

    ScreenScaffold {
        ScreenHeader(
            icon = HeraldMark,
            title = if (onCancel == null) "Welcome to Herald" else "Add a gateway",
            subtitle = if (onCancel == null) "Connect to your Hermes Agent gateway. Nothing is sent until you sign in."
            else "Connect to another Hermes Agent gateway. You stay signed in to the ones you saved.",
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

        var guideOpen by remember { mutableStateOf(false) }
        Button(
            text = if (guideOpen) "Hide address help" else "Which address do I use?",
            onClick = { guideOpen = !guideOpen },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Small,
            leadingIcon = if (guideOpen) Lucide.ChevronUp else Lucide.CircleHelp,
        )
        if (guideOpen) AddressGuide(Modifier.padding(horizontal = 4.dp))

        val result = state.result
        val accessSaved by viewModel.accessSaved.collectAsStateWithLifecycle()
        var accessOpen by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(result, state.accessError, accessSaved) {
            if (result is ProbeResult.AccessBlocked || state.accessError != null || accessSaved) accessOpen = true
        }
        Button(
            text = if (accessOpen) "Hide Cloudflare Access" else "Behind Cloudflare Access?",
            onClick = { accessOpen = !accessOpen },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Small,
            leadingIcon = if (accessOpen) Lucide.ChevronUp else Lucide.KeyRound,
        )
        if (accessOpen) AccessTokenFields(viewModel, state.accessError, accessSaved)

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

        if (result is ProbeResult.Reachable) ResultCard(result)

        // Each stage is tested on its own: a reachable server says nothing yet about the sign-in or chat.
        // A failed server carries its problem and fix here, so there's no separate result card for it.
        result?.let {
            Surface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectionChecklist(
                        results = it.serverOnlyResults(),
                        running = false,
                        pendingNote = { stage ->
                            when {
                                !signInReady -> "Needs a username and password sign-in, which this dashboard doesn't offer yet."
                                stage == CheckStage.SignIn -> "Tested when you sign in, next."
                                else -> "Tested after sign-in: the chat shows whether it connects, and Settings → Check connection tests it on its own."
                            }
                        },
                    )
                    if (it is ProbeResult.Unreachable) {
                        Hint("If the dashboard isn't running yet, run this on the server:")
                        CodeLine("hermes dashboard --host 0.0.0.0 --port 9119 --no-open")
                    }
                }
            }
        }

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

        if (onCancel != null) {
            Button(
                text = "Back to my gateway",
                onClick = onCancel,
                variant = ButtonVariant.Ghost,
                leadingIcon = Lucide.ArrowLeft,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The bundled username/password provider (`auth_providers` entry). */
private const val PASSWORD_PROVIDER = "basic"

/** A Cloudflare Access service token, sent with every request to this gateway's address. */
@Composable
private fun AccessTokenFields(viewModel: ConnectViewModel, error: String?, saved: Boolean) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint(
            "If Cloudflare Access protects this address, create a service token in Cloudflare Zero Trust " +
                "(Access → Service credentials) and add a Service Auth policy for it to the Access application.",
        )
        if (saved) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Status.Ok, "A service token is saved for this address")
                Button(
                    text = "Forget",
                    onClick = viewModel::forgetAccessToken,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Small,
                )
            }
            Hint("Leave the fields blank to keep using it, or enter a new token to replace it.")
        }
        TextField(
            state = viewModel.accessClientId,
            label = "Client ID",
            placeholder = "….access",
            error = error,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        TextField(
            state = viewModel.accessClientSecret,
            label = "Client Secret",
            password = true,
            supportingText = "Stored encrypted on this phone, and sent only to this address.",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            onKeyboardAction = { viewModel.testConnection() },
        )
    }
}

@Composable
private fun ResultCard(result: ProbeResult.Reachable) {
    Surface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReachableContent(result)
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

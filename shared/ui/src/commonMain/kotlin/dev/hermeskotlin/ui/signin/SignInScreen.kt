package dev.hermeskotlin.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Status
import dev.hermeskotlin.designsystem.components.StatusDot
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.ScreenHeader
import dev.hermeskotlin.ui.components.ScreenScaffold
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SignInScreen(
    gateway: SavedGateway,
    notice: String?,
    onSignedIn: () -> Unit,
    onChangeGateway: () -> Unit,
    viewModel: SignInViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val methods = state.methods

    // One view model serves every gateway's sign-in screen, so it starts over when the gateway changes. A
    // browser sign-in still running goes on across a rotation, and only ever signs its own gateway in.
    LaunchedEffect(gateway.url) { viewModel.load(gateway) }

    LaunchedEffect(state.signedInUrl) {
        if (state.signedInUrl == gateway.url) {
            viewModel.consumeSignedIn()
            onSignedIn()
        }
    }

    ScreenScaffold {
        ScreenHeader(
            icon = Lucide.KeyRound,
            title = "Sign in",
            subtitle = when {
                !methods.password -> "Sign in to ${gateway.gatewayUrl.host} in your browser, with the account its dashboard uses."
                methods.browser -> "Use the dashboard username and password configured on ${gateway.gatewayUrl.host}, or sign in in your browser."
                else -> "Use the dashboard username and password configured on ${gateway.gatewayUrl.host}."
            },
        )

        if (notice != null) StatusDot(Status.Warning, notice)

        if (methods.password) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextField(
                    state = viewModel.username,
                    label = "Username",
                    enabled = !state.signingIn,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                )
                TextField(
                    state = viewModel.password,
                    label = "Password",
                    password = true,
                    enabled = !state.signingIn,
                    error = state.error.takeUnless { methods.browser },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    onKeyboardAction = { viewModel.signIn(gateway) },
                )
            }

            Button(
                text = if (state.signingIn && !state.waitingForBrowser) "Signing in…" else "Sign in",
                onClick = { viewModel.signIn(gateway) },
                loading = state.signingIn && !state.waitingForBrowser,
                enabled = !state.waitingForBrowser,
                size = ButtonSize.Large,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (methods.browser) {
            if (methods.password) {
                Text(
                    "or",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Button(
                text = if (state.waitingForBrowser) "Waiting for the browser…" else "Sign in with browser",
                onClick = { viewModel.signInWithBrowser(gateway) { uriHandler.openUri(it) } },
                loading = state.waitingForBrowser,
                enabled = !state.signingIn,
                leadingIcon = Lucide.Globe,
                variant = if (methods.password) ButtonVariant.Secondary else ButtonVariant.Primary,
                size = ButtonSize.Large,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.waitingForBrowser) {
                Text(
                    "Finish signing in on the page that opened. Herald comes back by itself when you're done.",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textSecondary],
                )
                Button(
                    text = "Cancel",
                    onClick = viewModel::cancelBrowser,
                    variant = ButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
        }

        Button(
            text = "Use a different gateway",
            onClick = onChangeGateway,
            variant = ButtonVariant.Ghost,
            leadingIcon = Lucide.ArrowLeft,
            enabled = !state.signingIn,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

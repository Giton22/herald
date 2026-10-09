package dev.hermeskotlin.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Status
import dev.hermeskotlin.designsystem.components.StatusDot
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.textMuted
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

    // One view model serves every gateway's sign-in screen, so it starts over when the gateway changes. A
    // browser sign-in still running goes on across a rotation, and only ever signs its own gateway in.
    LaunchedEffect(gateway.url) { viewModel.load(gateway) }

    LaunchedEffect(state.signedInUrl) {
        if (state.signedInUrl == gateway.url) {
            viewModel.consumeSignedIn()
            onSignedIn()
        }
    }

    SignInView(
        host = gateway.gatewayUrl.host,
        notice = notice,
        state = state,
        username = viewModel.username,
        password = viewModel.password,
        onSignIn = { viewModel.signIn(gateway) },
        onSignInWithBrowser = { viewModel.signInWithBrowser(gateway) { uriHandler.openUri(it) } },
        onCancelBrowser = viewModel::cancelBrowser,
        onChangeGateway = onChangeGateway,
    )
}

/**
 * The gateway it's for on a chip, then the ways in it offers: the dashboard's username and password, the
 * browser, or both with an "or" between them.
 */
@Composable
internal fun SignInView(
    host: String,
    notice: String?,
    state: SignInUiState,
    username: TextFieldState,
    password: TextFieldState,
    onSignIn: () -> Unit,
    onSignInWithBrowser: () -> Unit,
    onCancelBrowser: () -> Unit,
    onChangeGateway: () -> Unit,
) {
    val methods = state.methods
    ScreenScaffold {
        GatewayChip(host)

        ScreenHeader(
            icon = Lucide.KeyRound,
            title = "Sign in",
            subtitle = when {
                !methods.password -> "Sign in to $host in your browser, with the account its dashboard uses."
                methods.browser -> "Use the dashboard username and password configured on $host, or sign in in your browser."
                else -> "Use the dashboard username and password configured on $host."
            },
        )

        if (notice != null) StatusDot(Status.Warning, notice)

        if (methods.password) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TextField(
                    state = username,
                    label = "Username",
                    enabled = !state.signingIn,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                )
                TextField(
                    state = password,
                    label = "Password",
                    password = true,
                    enabled = !state.signingIn,
                    error = state.error.takeUnless { methods.browser },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    onKeyboardAction = { onSignIn() },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (methods.password) {
                Button(
                    text = if (state.signingIn && !state.waitingForBrowser) "Signing in…" else "Sign in",
                    onClick = onSignIn,
                    loading = state.signingIn && !state.waitingForBrowser,
                    enabled = !state.waitingForBrowser,
                    size = ButtonSize.Large,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (methods.browser) {
                if (methods.password) OrDivider()
                Button(
                    text = if (state.waitingForBrowser) "Waiting for the browser…" else "Sign in with browser",
                    onClick = onSignInWithBrowser,
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
                        onClick = onCancelBrowser,
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
}

/**
 * The gateway being signed in to, its host in mono on a ringed pill. The screen can open without a fresh test
 * (a sign-in that ran out), so the chip doesn't claim the gateway answered.
 */
@Composable
private fun GatewayChip(host: String) {
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .background(Theme[colors][surface], CircleShape)
            .border(1.dp, Theme[colors][stroke], CircleShape)
            .padding(start = 6.dp, end = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Gateway $host" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).background(Theme[colors][surface2], CircleShape), contentAlignment = Alignment.Center) {
            UnstyledIcon(Lucide.Globe, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(11.dp))
        }
        Text(
            host,
            style = Theme[typography][code].copy(fontSize = 12.sp),
            color = Theme[colors][textSecondary],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "or" between two hairlines, between the password and the browser. */
@Composable
private fun OrDivider() {
    val line = Theme[colors][stroke]
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text("or", style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textMuted])
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

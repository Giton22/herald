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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource
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
            title = stringResource(Res.string.signin_title),
            subtitle = when {
                !methods.password -> stringResource(Res.string.signin_detail_browser, host)
                methods.browser -> stringResource(Res.string.signin_detail_both, host)
                else -> stringResource(Res.string.signin_detail_password, host)
            },
        )

        if (notice != null) StatusDot(Status.Warning, notice)

        if (methods.password) {
            // With a keyboard, the page opens ready for the username to be typed.
            val usernameFocus = remember { FocusRequester() }
            if (isDesktop) LaunchedEffect(Unit) { usernameFocus.requestFocus() }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TextField(
                    state = username,
                    focusRequester = usernameFocus,
                    label = stringResource(Res.string.signin_username),
                    enabled = !state.signingIn,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                )
                TextField(
                    state = password,
                    label = stringResource(Res.string.signin_password),
                    password = true,
                    enabled = !state.signingIn,
                    error = state.error.takeUnless { methods.browser }?.let { signInErrorText(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    onKeyboardAction = { onSignIn() },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (methods.password) {
                Button(
                    text = stringResource(if (state.signingIn && !state.waitingForBrowser) Res.string.signin_signing_in else Res.string.signin_title),
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
                    text = stringResource(if (state.waitingForBrowser) Res.string.signin_waiting_browser else Res.string.signin_with_browser),
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
                        stringResource(Res.string.signin_finish_in_browser),
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textSecondary],
                    )
                    Button(
                        text = stringResource(Res.string.common_cancel),
                        onClick = onCancelBrowser,
                        variant = ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.error?.let { Text(signInErrorText(it), style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
            }

            Button(
                text = stringResource(Res.string.signin_different_gateway),
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
    val spoken = stringResource(Res.string.signin_gateway_chip, host)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .background(Theme[colors][surface], CircleShape)
            .border(1.dp, Theme[colors][stroke], CircleShape)
            .padding(start = 6.dp, end = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
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

/** Why the sign-in didn't work, in the app's language; the gateway's and the network's own words as they came. */
@Composable
private fun signInErrorText(error: SignInError): String = when (error) {
    SignInError.MissingFields -> stringResource(Res.string.signin_error_missing)
    SignInError.WrongCredentials -> stringResource(Res.string.signin_error_wrong)
    SignInError.Rejected -> stringResource(Res.string.signin_error_rejected)
    SignInError.RateLimited -> stringResource(Res.string.signin_error_rate_limited)
    is SignInError.Unreachable -> stringResource(Res.string.signin_error_unreachable, error.message)
    is SignInError.Gateway -> error.message
}

/** "or" between two hairlines, between the password and the browser. */
@Composable
private fun OrDivider() {
    val line = Theme[colors][stroke]
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text(stringResource(Res.string.signin_or), style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textMuted])
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

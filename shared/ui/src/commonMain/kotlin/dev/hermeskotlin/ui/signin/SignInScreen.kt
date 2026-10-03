package dev.hermeskotlin.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Status
import dev.hermeskotlin.designsystem.components.StatusDot
import dev.hermeskotlin.designsystem.components.TextField
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

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            viewModel.consumeSignedIn()
            onSignedIn()
        }
    }

    ScreenScaffold {
        ScreenHeader(
            icon = Lucide.KeyRound,
            title = "Sign in",
            subtitle = "Use the dashboard username and password configured on ${gateway.gatewayUrl.host}.",
        )

        if (notice != null) StatusDot(Status.Warning, notice)

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
                error = state.error,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                onKeyboardAction = { viewModel.signIn(gateway) },
            )
        }

        Button(
            text = if (state.signingIn) "Signing in…" else "Sign in",
            onClick = { viewModel.signIn(gateway) },
            loading = state.signingIn,
            size = ButtonSize.Large,
            modifier = Modifier.fillMaxWidth(),
        )

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

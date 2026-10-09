package dev.hermeskotlin.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PlugZap
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.core.gateway.ProbeResult
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.gateway.serverOnlyResults
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Status
import dev.hermeskotlin.designsystem.components.StatusDot
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.successSoft
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
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
    val accessSaved by viewModel.accessSaved.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.url.text.toString() }.drop(1).collect { viewModel.onUrlEdited() }
    }

    ConnectView(
        url = viewModel.url,
        state = state,
        access = AccessToken(viewModel.accessClientId, viewModel.accessClientSecret, accessSaved, viewModel::forgetAccessToken),
        onTest = viewModel::testConnection,
        onContinue = { onContinue(SavedGateway(it.url.value, provider = PASSWORD_PROVIDER)) },
        onCancel = onCancel,
    )
}

/** The Cloudflare Access service token fields, and whether one is saved for this address already. */
internal class AccessToken(
    val clientId: TextFieldState,
    val clientSecret: TextFieldState,
    val saved: Boolean,
    val onForget: () -> Unit,
)

/**
 * The address, tested from inside its field; what the test found; help on which address to use and on
 * Cloudflare Access; and, once the gateway answers with a sign-in the app can use, the way on to it.
 */
@Composable
internal fun ConnectView(
    url: TextFieldState,
    state: ConnectUiState,
    access: AccessToken,
    onTest: () -> Unit,
    onContinue: (ProbeResult.Reachable) -> Unit,
    onCancel: (() -> Unit)?,
) {
    val result = state.result
    ScreenScaffold {
        ScreenHeader(
            icon = if (onCancel == null) null else Lucide.PlugZap,
            title = if (onCancel == null) "Welcome to Herald" else "Add a gateway",
            subtitle = if (onCancel == null) "Connect to your Hermes Agent gateway. Nothing is sent until you sign in."
            else "Connect to another Hermes Agent gateway. You stay signed in to the ones you saved.",
        )

        TextField(
            state = url,
            label = "Gateway address",
            placeholder = "100.64.0.1:9119",
            supportingText = "Where your hermes dashboard runs: LAN, Tailscale or HTTPS.",
            error = state.urlError,
            leadingIcon = Lucide.Globe,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            onKeyboardAction = { onTest() },
            trailing = { TestButton(state.testing, onTest) },
        )

        if (result is ProbeResult.Reachable) ResultCard(result)

        // Anything short of a reachable server goes down the stages, with its problem and fix on the first.
        if (result != null && result !is ProbeResult.Reachable) {
            Surface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectionChecklist(results = result.serverOnlyResults(), running = false)
                    if (result is ProbeResult.Unreachable) {
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

        Column {
            var guideOpen by remember { mutableStateOf(false) }
            HelpRow(Lucide.CircleHelp, "Which address do I use?", guideOpen) { guideOpen = !guideOpen }
            if (guideOpen) AddressGuide(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp))

            var accessOpen by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(result, state.accessError, access.saved) {
                if (result is ProbeResult.AccessBlocked || state.accessError != null || access.saved) accessOpen = true
            }
            HelpRow(Lucide.KeyRound, "Behind Cloudflare Access?", accessOpen) { accessOpen = !accessOpen }
            if (accessOpen) AccessTokenFields(access, state.accessError, onTest)
        }

        val signInReady = result is ProbeResult.Reachable && result.status.authRequired && result.canSignIn
        if (signInReady) {
            Button(
                text = "Continue to sign in",
                onClick = { onContinue(result as ProbeResult.Reachable) },
                trailingIcon = Lucide.ArrowRight,
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

/** Test, inside the address field's end: a raised 40dp chip in a full-height touch target. */
@Composable
private fun TestButton(testing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val tint = Theme[colors][text]
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(interaction, indication = null, enabled = !testing, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Theme[colors][surface3])
                .indication(interaction, rememberColoredIndication(tint))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (testing) Spinner(Modifier.size(14.dp), color = tint)
            else UnstyledIcon(Lucide.PlugZap, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Text(
                if (testing) "Testing…" else "Test",
                style = Theme[typography][label].copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = tint,
                singleLine = true,
            )
        }
    }
}

/** A quiet row that opens help underneath it: an icon, what it's about, and a chevron that turns when open. */
@Composable
private fun HelpRow(icon: ImageVector, title: String, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(15.dp))
        Text(title, style = Theme[typography][bodySmall].copy(fontSize = 13.5.sp), color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
        UnstyledIcon(
            if (open) Lucide.ChevronDown else Lucide.ChevronRight,
            contentDescription = null,
            tint = Theme[colors][textMuted],
            modifier = Modifier.size(13.dp),
        )
    }
}

/** A Cloudflare Access service token, sent with every request to this gateway's address. */
@Composable
private fun AccessTokenFields(access: AccessToken, error: String?, onTest: () -> Unit) {
    Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint(
            "If Cloudflare Access protects this address, create a service token in Cloudflare Zero Trust " +
                "(Access → Service credentials) and add a Service Auth policy for it to the Access application.",
        )
        if (access.saved) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Status.Ok, "A service token is saved for this address")
                Button(
                    text = "Forget",
                    onClick = access.onForget,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Small,
                )
            }
            Hint("Leave the fields blank to keep using it, or enter a new token to replace it.")
        }
        TextField(
            state = access.clientId,
            label = "Client ID",
            placeholder = "….access",
            error = error,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        TextField(
            state = access.clientSecret,
            label = "Client Secret",
            password = true,
            supportingText = "Stored encrypted on this phone, and sent only to this address.",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            onKeyboardAction = { onTest() },
        )
    }
}

/** A gateway that answered: a check, its version, what's next, then what it said under a hairline. */
@Composable
private fun ResultCard(result: ProbeResult.Reachable) {
    val status = result.status
    val signInReady = status.authRequired && result.canSignIn
    Surface(Modifier.fillMaxWidth()) {
        Column(Modifier.semantics(mergeDescendants = true) { }) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(Theme[colors][successSoft], CircleShape), contentAlignment = Alignment.Center) {
                    UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][success], modifier = Modifier.size(15.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        status.version?.let { "Hermes $it is reachable" } ?: "Hermes is reachable",
                        style = Theme[typography][bodySmall].copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        color = Theme[colors][text],
                    )
                    if (signInReady) {
                        Text("Sign-in is tested next", style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textTertiary])
                    }
                }
            }
            val line = Theme[colors][stroke]
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoRow("Agent gateway", if (status.gatewayRunning) "Running" else "Stopped")
                InfoRow(
                    "Sign-in",
                    when {
                        !status.authRequired -> "Not required"
                        status.supportsPasswordLogin && status.supportsNativeSignIn -> "Password or browser"
                        status.supportsPasswordLogin -> "Username & password"
                        status.supportsNativeSignIn -> "In the browser (SSO)"
                        else -> "Unsupported"
                    },
                )
                if (status.profiles.isNotEmpty()) InfoRow("Profiles", status.profiles.joinToString())

                if (!result.canSignIn) {
                    StatusDot(Status.Warning, "No sign-in the app can use", Modifier.padding(top = 4.dp))
                    Hint("This dashboard offers neither a username/password provider nor browser sign-in. Update Hermes, or configure the username/password provider on the server.")
                } else if (!status.authRequired) {
                    StatusDot(Status.Warning, "Auth gate is off", Modifier.padding(top = 4.dp))
                    Hint("The dashboard is bound to loopback, so other devices won't be allowed to chat. Bind it with --host 0.0.0.0 and configure a password provider.")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(name, style = Theme[typography][bodySmall].copy(fontSize = 13.5.sp), color = Theme[colors][textTertiary])
        Text(
            value,
            style = Theme[typography][bodySmall].copy(fontSize = 13.5.sp),
            color = Theme[colors][text],
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
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

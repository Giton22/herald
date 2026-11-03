package dev.hermeskotlin.ui.connect

import dev.hermeskotlin.designsystem.components.handCursor

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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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
import com.composables.icons.lucide.ShieldCheck
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.rememberHoverIndication
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.InvalidGatewayUrlException
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
import dev.hermeskotlin.ui.components.rememberClientCertPicker
import dev.hermeskotlin.ui.CopyableOnDesktop
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.resources.*
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.stringResource
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
    val certAlias by viewModel.certAlias.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.url.text.toString() }.drop(1).collect { viewModel.onUrlEdited() }
    }

    ConnectView(
        url = viewModel.url,
        state = state,
        access = remember(viewModel, accessSaved) {
            AccessToken(viewModel.accessClientId, viewModel.accessClientSecret, accessSaved, viewModel::forgetAccessToken)
        },
        cert = remember(viewModel, certAlias) {
            ClientCert(certAlias, viewModel::pickClientCert, viewModel::clearClientCert)
        },
        onTest = viewModel::testConnection,
        onContinue = { onContinue(SavedGateway(it.url.value, provider = PASSWORD_PROVIDER, clientCertAlias = certAlias)) },
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

/** The client certificate picked for the address typed, shown by its system-store alias. */
internal class ClientCert(
    val alias: String?,
    val onPick: (String) -> Unit,
    val onClear: () -> Unit,
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
    cert: ClientCert,
    onTest: () -> Unit,
    onContinue: (ProbeResult.Reachable) -> Unit,
    onCancel: (() -> Unit)?,
) {
    val result = state.result
    val parsed = GatewayUrl.parseOrNull(url.text.toString())
    val requested = url.text.toString()
    val picker = key(requested) {
        val host = parsed?.takeIf { !isDesktop || it.value.startsWith("https://") }?.host
        rememberClientCertPicker(host, parsed?.port ?: -1, onPicked = { alias ->
            if (alias != null && url.text.toString() == requested) cert.onPick(alias)
        })
    }
    ScreenScaffold {
        ScreenHeader(
            icon = if (onCancel == null) null else Lucide.PlugZap,
            title = stringResource(if (onCancel == null) Res.string.connect_welcome else Res.string.connect_add_gateway),
            subtitle = stringResource(if (onCancel == null) Res.string.connect_welcome_detail else Res.string.connect_add_gateway_detail),
        )

        // With a keyboard, the page opens ready for the address to be typed.
        val urlFocus = remember { FocusRequester() }
        if (isDesktop) LaunchedEffect(Unit) { urlFocus.requestFocus() }
        val signInReady = result is ProbeResult.Reachable && result.status.authRequired && result.canSignIn
        // The address as it was when tested: Enter again on it, unchanged, goes on to sign in.
        var testedAs by remember { mutableStateOf<String?>(null) }
        val test = {
            testedAs = url.text.toString()
            onTest()
        }
        val enter = {
            if (isDesktop && signInReady && url.text.toString() == testedAs) onContinue(result as ProbeResult.Reachable) else test()
        }
        TextField(
            state = url,
            focusRequester = urlFocus,
            label = stringResource(Res.string.connect_address),
            placeholder = "100.64.0.1:9119",
            supportingText = stringResource(Res.string.connect_address_detail),
            error = state.urlError?.let { urlErrorText(it) },
            leadingIcon = Lucide.Globe,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            onKeyboardAction = { enter() },
            trailing = { TestButton(state.testing, test) },
        )

        if (result is ProbeResult.Reachable) ResultCard(result)

        // Anything short of a reachable server goes down the stages, with its problem and fix on the first.
        if (result != null && result !is ProbeResult.Reachable) {
            Surface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectionChecklist(results = result.serverOnlyResults(), running = false)
                    if (result is ProbeResult.Unreachable) {
                        Hint(stringResource(Res.string.connect_start_dashboard))
                        CodeLine("hermes dashboard --host 0.0.0.0 --port 9119 --no-open")
                    }
                }
            }
        }

        if (result is ProbeResult.Reachable && result.url.isExposed) {
            Text(
                stringResource(Res.string.connect_exposed),
                style = Theme[typography][bodySmall],
                color = Theme[colors][danger],
            )
        }

        Column {
            var guideOpen by remember { mutableStateOf(false) }
            HelpRow(Lucide.CircleHelp, stringResource(Res.string.connect_which_address), guideOpen) { guideOpen = !guideOpen }
            if (guideOpen) AddressGuide(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp))

            var accessOpen by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(result, state.accessError, access.saved) {
                if (result is ProbeResult.AccessBlocked || state.accessError != null || access.saved) accessOpen = true
            }
            HelpRow(Lucide.KeyRound, stringResource(Res.string.connect_behind_access), accessOpen) { accessOpen = !accessOpen }
            if (accessOpen) AccessTokenFields(access, state.accessError, test)

            if (picker.available) {
                var certOpen by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(result) {
                    if (result is ProbeResult.ClientCertRequired) certOpen = true
                }
                HelpRow(Lucide.ShieldCheck, stringResource(Res.string.connect_need_cert), certOpen) { certOpen = !certOpen }
                if (certOpen) ClientCertFields(cert, demanded = result is ProbeResult.ClientCertRequired, onPick = picker::pick)
            }
        }

        if (signInReady) {
            Button(
                text = stringResource(Res.string.connect_continue),
                onClick = { onContinue(result as ProbeResult.Reachable) },
                trailingIcon = Lucide.ArrowRight,
                size = ButtonSize.Large,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (onCancel != null) {
            Button(
                text = stringResource(Res.string.connect_back_to_gateway),
                onClick = onCancel,
                variant = ButtonVariant.Ghost,
                leadingIcon = Lucide.ArrowLeft,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Why the address typed isn't one. */
@Composable
internal fun urlErrorText(error: InvalidGatewayUrlException): String = when (error.problem) {
    InvalidGatewayUrlException.Problem.Required -> stringResource(Res.string.connect_url_required)
    InvalidGatewayUrlException.Problem.Scheme -> stringResource(Res.string.connect_url_scheme, error.detail)
    InvalidGatewayUrlException.Problem.Invalid -> stringResource(Res.string.connect_url_invalid, error.detail)
    InvalidGatewayUrlException.Problem.NoHost -> stringResource(Res.string.connect_url_no_host)
}

@Composable
private fun accessErrorText(problem: AccessTokenProblem): String = when (problem) {
    AccessTokenProblem.HalfFilled -> stringResource(Res.string.connect_access_half_filled)
}

/** The bundled username/password provider (`auth_providers` entry). */
private const val PASSWORD_PROVIDER = "basic"

/** Test, inside the address field's end: a raised 40dp chip in a full-height touch target. */
@Composable
private fun TestButton(testing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val tint = Theme[colors][text]
    val spoken = stringResource(if (testing) Res.string.connect_testing_connection else Res.string.connect_test_connection)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .handCursor(!testing)
            .clickable(interaction, indication = null, enabled = !testing, role = Role.Button, onClick = onClick)
            // "Test" alone doesn't say what's tested once it's read apart from the field.
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                if (testing) disabled()
                onClick { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Theme[colors][surface3])
                .indication(interaction, rememberHoverIndication(tint))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (testing) Spinner(Modifier.size(14.dp), color = tint)
            else UnstyledIcon(Lucide.PlugZap, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Text(
                stringResource(if (testing) Res.string.connect_testing else Res.string.connect_test),
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
    val state = stringResource(if (open) Res.string.app_expanded else Res.string.app_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
            .handCursor()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = state }
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
private fun AccessTokenFields(access: AccessToken, error: AccessTokenProblem?, onTest: () -> Unit) {
    Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint(stringResource(Res.string.connect_access_hint))
        if (access.saved) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Status.Ok, stringResource(Res.string.connect_access_saved))
                Button(
                    text = stringResource(Res.string.connect_forget),
                    onClick = access.onForget,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Small,
                )
            }
            Hint(stringResource(Res.string.connect_access_keep))
        }
        TextField(
            state = access.clientId,
            label = stringResource(Res.string.connect_client_id),
            placeholder = "….access",
            error = error?.let { accessErrorText(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        TextField(
            state = access.clientSecret,
            label = stringResource(Res.string.connect_client_secret),
            password = true,
            supportingText = stringResource(if (isDesktop) Res.string.connect_client_secret_detail_desktop else Res.string.connect_client_secret_detail),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            onKeyboardAction = { onTest() },
        )
    }
}

/** A client certificate selected for the proxy at this address. */
@Composable
private fun ClientCertFields(cert: ClientCert, demanded: Boolean, onPick: () -> Unit) {
    Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint(stringResource(if (demanded) Res.string.connect_cert_demanded else Res.string.connect_cert_hint))
        if (cert.alias != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Status.Ok, cert.alias)
                Button(
                    text = if (isDesktop) "Clear selection" else stringResource(Res.string.connect_forget),
                    onClick = cert.onClear,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Small,
                )
            }
            Hint(if (isDesktop) "Imported keys are stored encrypted for this TLS hostname. Clearing this selection may still use another saved certificate for the same hostname. Imported keys remain in encrypted settings."
                else stringResource(Res.string.connect_cert_kept))
        } else {
            Button(
                text = stringResource(Res.string.connect_pick_cert),
                onClick = onPick,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
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
                        status.version?.let { stringResource(Res.string.connect_reachable_version, it) } ?: stringResource(Res.string.connect_reachable),
                        style = Theme[typography][bodySmall].copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        color = Theme[colors][text],
                    )
                    if (signInReady) {
                        Text(stringResource(Res.string.connect_tested_next), style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textTertiary])
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
                InfoRow(
                    stringResource(Res.string.connect_agent_gateway),
                    stringResource(if (status.gatewayRunning) Res.string.connect_running else Res.string.connect_stopped),
                )
                InfoRow(
                    stringResource(Res.string.check_stage_sign_in),
                    stringResource(
                        when {
                            !status.authRequired -> Res.string.connect_sign_in_not_required
                            status.supportsPasswordLogin && status.supportsNativeSignIn -> Res.string.connect_sign_in_password_or_browser
                            status.supportsPasswordLogin -> Res.string.connect_sign_in_password
                            status.supportsNativeSignIn -> Res.string.connect_sign_in_browser
                            else -> Res.string.connect_sign_in_unsupported
                        },
                    ),
                )
                if (status.profiles.isNotEmpty()) InfoRow(stringResource(Res.string.connect_profiles), status.profiles.joinToString())

                if (!result.canSignIn) {
                    StatusDot(Status.Warning, stringResource(Res.string.connect_no_sign_in), Modifier.padding(top = 4.dp))
                    Hint(stringResource(Res.string.connect_no_sign_in_detail))
                } else if (!status.authRequired) {
                    StatusDot(Status.Warning, stringResource(Res.string.connect_auth_off), Modifier.padding(top = 4.dp))
                    Hint(stringResource(Res.string.connect_auth_off_detail))
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
    CopyableOnDesktop { Text(command, style = Theme[typography][code], color = Theme[colors][text]) }
}

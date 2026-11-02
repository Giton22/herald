package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.hermeskotlin.core.plugins.GitInstallResult
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant

@Composable
internal fun GitInstallSheet(state: GitInstallUi, onDismiss: () -> Unit, onInstall: () -> Unit, onReset: () -> Unit) {
    BottomSheet(visible = state.visible, onDismiss = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Set up Agent Screen")
            Text("Install and enable Agent Screen on ${state.gateway}'s launch profile. Plugin code runs on the gateway; continue only if you trust it. The gateway reports the source and revision; these are not independently verified.")
            Text("Separately configure HERMES_SCREEN_GATEWAY_PORT to the trusted same gateway's IPv4 loopback HTTP WebSocket port, and install a compatible TigerVNC and desktop. Enablement does not mean Screen is ready; secondary profiles in a shared dashboard are unsupported.")
            if (state.busy) Text("Setting up… Do not retry if interrupted; inspect the gateway first.")
            when (val result = state.result) {
                is GitInstallResult.Reported -> Text("Gateway reports Agent Screen enabled. This does not verify desktop or route readiness.")
                is GitInstallResult.Existing -> Text(result.detail)
                is GitInstallResult.Uncertain -> {
                    Text(result.detail)
                    if (!state.busy) Button("I inspected the gateway and confirmed the operation stopped — clear warning", onClick = onReset, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
                }
                is GitInstallResult.Refused -> Text(result.detail)
                null -> if (!state.busy) Button("Install and enable", onClick = onInstall, size = ButtonSize.Small)
            }
            if (!state.busy) Button("Close", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
        }
    }
}

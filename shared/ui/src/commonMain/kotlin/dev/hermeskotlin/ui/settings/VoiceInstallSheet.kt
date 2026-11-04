package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.core.plugins.VoicePluginInstaller
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import com.composeunstyled.Text

@Composable
internal fun VoiceInstallSheet(state: VoiceInstallUi, onDismiss: () -> Unit, onSelect: (String) -> Unit, onConfirm: () -> Unit) {
    BottomSheet(visible = state.visible, onDismiss = onDismiss) {
        Column(Modifier.padding(20.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Install subscription voice plugin")
            Text("Gateway: ${state.gateway}")
            Text("Source: ${VoicePluginInstaller.SOURCE}\nConfigured revision (review not verified): ${state.revision.ifEmpty { "not configured" }}")
            if (state.loading) Text("Contacting gateway…")
            state.error?.let { Text(it) }
            if (state.profiles.isNotEmpty()) {
                Text("Select profiles (including the launch/default profile if needed):")
                state.profiles.forEach { profile ->
                    Button("${if (profile in state.selected) "☑" else "☐"} $profile", onClick = { onSelect(profile) }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
                }
                Text("Selected: ${state.selected.joinToString().ifEmpty { "none" }}")

                Text("Installing executes plugin code and dependencies on the gateway. Voice audio, chat history and profile voice instructions may be sent to the voice provider; delegated chat may incur separate charges. Existing copies will not be replaced; no restart is forced.")
                Text("Compatibility checks assume a trusted, unmodified upstream-compatible gateway. Forks or backports may behave differently. Checks do not verify gateway identity, source review, plugin provenance, runtime readiness, or profile isolation.")
                state.results?.forEach { (profile, result) -> Text("$profile: $result") }
                if (state.error == null && state.selected.isNotEmpty() && state.results == null && !state.loading) {
                    Button("Consent and install", onClick = onConfirm, size = ButtonSize.Small)
                }
            }
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
        }
    }
}

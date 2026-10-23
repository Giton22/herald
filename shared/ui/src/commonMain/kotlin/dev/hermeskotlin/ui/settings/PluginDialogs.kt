package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import dev.hermeskotlin.core.plugins.NextPromptApi
import dev.hermeskotlin.core.push.PluginUpdate
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.ui.resources.Res
import org.jetbrains.compose.resources.stringResource

/** Where the plugin comes from, as the dialogs name it. */
private val PLUGIN_SOURCE = PushApi.PLUGIN_REPO.substringAfter("://")

/**
 * Asked before a feature that needs the herald-push plugin is turned on: Herald installs code on the user's
 * gateway, so it says so first. [what] is what the plugin does for this feature.
 */
@Composable
internal fun PluginInstallDialog(visible: Boolean, feature: String, what: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.plugin_install_title),
        message = stringResource(
            Res.string.plugin_install_message,
            feature,
            PushApi.PLUGIN_VERSION,
            PLUGIN_SOURCE,
            what,
        ),
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(stringResource(Res.string.common_turn_on), onClick = onConfirm, size = ButtonSize.Small)
        },
    )
}

/**
 * Asked before Herald puts the Next Prompt plugin on the gateway: it's someone else's code, it runs after every
 * turn, and it sends the end of each chat to a model, which costs a call each time.
 */
@Composable
internal fun SuggestionsInstallDialog(visible: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.plugin_next_prompt_title),
        message = stringResource(
            Res.string.plugin_next_prompt_message,
            NextPromptApi.PLUGIN_VERSION,
            NextPromptApi.PLUGIN_REPO.substringAfter("://"),
        ),
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(stringResource(Res.string.common_install), onClick = onConfirm, size = ButtonSize.Small)
        },
    )
}

/** A newer plugin is available on the gateway or a bot; ask before replacing any installed copies. */
@Composable
internal fun PluginUpdateRow(update: PluginUpdate, onUpdate: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    ActionRow(
        if (update.needed) {
            stringResource(Res.string.plugin_update_needed_row, update.available)
        } else {
            stringResource(Res.string.plugin_update_row, update.installed, update.available)
        },
        Lucide.RefreshCw,
    ) { asking = true }
    Dialog(
        visible = asking,
        onDismissRequest = { asking = false },
        title = stringResource(Res.string.plugin_update_title),
        // The closing sentence is its own line, so each language can end the paragraph its own way.
        message = stringResource(Res.string.plugin_update_message, update.available, PLUGIN_SOURCE) + " " +
            stringResource(
                if (update.needed) Res.string.plugin_update_needs_it else Res.string.plugin_update_optional,
            ),
        actions = {
            Button(stringResource(Res.string.common_not_now), onClick = { asking = false }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                stringResource(Res.string.common_update),
                onClick = {
                    asking = false
                    onUpdate()
                },
                size = ButtonSize.Small,
            )
        },
    )
}

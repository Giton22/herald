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
import dev.hermeskotlin.ui.i18n.Strings

/** Where the plugin comes from, as the dialogs name it. */
private val PLUGIN_SOURCE = PushApi.PLUGIN_REPO.substringAfter("://")

/**
 * Asked before a feature that needs the herald-push plugin is turned on: Kyllene installs code on the user's
 * gateway, so it says so first. [what] is what the plugin does for this feature.
 */
@Composable
internal fun PluginInstallDialog(visible: Boolean, feature: String, what: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = Strings.t("Install on the gateway?"),
        message = Strings.t(
            "{0} needs the herald-push plugin on your gateway. If it isn't there yet, Kyllene installs " +
                "version {1} from {2}, at the commit this Kyllene was reviewed with. {3} " +
                "Updates are only installed when you choose to, and you can remove the plugin on the gateway at any time.",
            Strings.t(feature),
            PushApi.PLUGIN_VERSION,
            PLUGIN_SOURCE,
            Strings.t(what),
        ),
        actions = {
            Button(Strings.t("Cancel"), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(Strings.t("Turn on"), onClick = onConfirm, size = ButtonSize.Small)
        },
    )
}

/**
 * Asked before Kyllene puts the Next Prompt plugin on the gateway: it's someone else's code, it runs after every
 * turn, and it sends the end of each chat to a model, which costs a call each time.
 */
@Composable
internal fun SuggestionsInstallDialog(visible: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = Strings.t("Install Next Prompt?"),
        message = Strings.t(
            "Kyllene installs Next Prompt {0}, a community plugin, from " +
                "{1}, at the commit this Kyllene was reviewed with. After " +
                "every turn, in Kyllene, Hermes Desktop and the terminal alike, it sends the last few messages of the chat to " +
                "your model (or the one set for its auxiliary task) and keeps the reply it suggests. That's one more " +
                "model call per turn. You can remove it on the gateway at any time.",
            NextPromptApi.PLUGIN_VERSION,
            NextPromptApi.PLUGIN_REPO.substringAfter("://"),
        ),
        actions = {
            Button(Strings.t("Cancel"), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(Strings.t("Install"), onClick = onConfirm, size = ButtonSize.Small)
        },
    )
}

/** A newer plugin is available on the gateway or a bot; ask before replacing any installed copies. */
@Composable
internal fun PluginUpdateRow(update: PluginUpdate, onUpdate: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    ActionRow(
        if (update.needed) {
            Strings.t("Update the gateway's plugin to {0}", update.available)
        } else {
            Strings.t("Plugin update: {0} → {1}", update.installed, update.available)
        },
        Lucide.RefreshCw,
    ) { asking = true }
    Dialog(
        visible = asking,
        onDismissRequest = { asking = false },
        title = Strings.t("Update the plugin?"),
        message = Strings.t(
            "Kyllene updates older herald-push copies on the gateway and its bot profiles to {0} from " +
                "{1}, at the commit this Kyllene was reviewed with. Disabled copies stay disabled. " +
                "Running chats gain new tools when their agent session reloads. {2}",
            update.available,
            PLUGIN_SOURCE,
            if (update.needed) Strings.t("This Kyllene needs the update.") else Strings.t("Updates are only installed when you choose to."),
        ),
        actions = {
            Button(Strings.t("Not now"), onClick = { asking = false }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                Strings.t("Update"),
                onClick = {
                    asking = false
                    onUpdate()
                },
                size = ButtonSize.Small,
            )
        },
    )
}

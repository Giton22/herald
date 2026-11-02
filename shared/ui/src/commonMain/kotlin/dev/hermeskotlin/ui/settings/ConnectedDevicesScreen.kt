package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Watch
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.sessions.SubpageHeader
import dev.hermeskotlin.wearlink.PebbleLink
import kotlinx.coroutines.flow.drop

/** Settings › Connected devices: the watches Kyllene talks to, and how. Saved as it changes. */
@Composable
fun ConnectedDevicesScreen(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit, onBack: () -> Unit) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    ConnectedDevicesView(settings, onUpdate, onBack)
}

/** The page itself, stateless but for the prompts being edited, so previews can draw it. */
@Composable
internal fun ConnectedDevicesView(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader("Connected devices", onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Section("Pebble") {
                    SwitchRow(
                        title = "Kyllene on the Pebble",
                        detail = "Talk to Hermes from Kyllene's Pebble watchapp, and let it ring there. Off, the watchapp " +
                            "says so and nothing goes to it.",
                        checked = settings.pebble,
                        onCheckedChange = { on -> onUpdate { it.copy(pebble = on) } },
                        icon = Lucide.Watch,
                    )
                    if (settings.pebble) {
                        Divider()
                        SwitchRow(
                            title = "Answer on the Pebble",
                            detail = "Approvals and questions can be answered on the watch, which doesn't ask for this " +
                                "phone's unlock: anyone wearing it can answer them.",
                            checked = settings.pebbleAnswers,
                            onCheckedChange = { on -> onUpdate { it.copy(pebbleAnswers = on) } },
                            icon = Lucide.ShieldAlert,
                        )
                    }
                }
                if (settings.pebble) {
                    Section("Pebble prompts") {
                        Text(
                            "Hold Select on the watch for these, and one press asks it. They go to the watch the next " +
                                "time it checks in with the phone, and stay there.",
                            style = Theme[typography][bodySmall],
                            color = Theme[colors][textTertiary],
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp),
                        )
                        PromptsEditor(settings.pebblePrompts) { prompts -> onUpdate { it.copy(pebblePrompts = prompts) } }
                    }
                }
                Section("Wear OS") {
                    Text(
                        "Kyllene on a Wear OS watch keeps its own settings: on the watch, open Kyllene's chats and tap " +
                            "Settings. Whether it rings along with the phone is in Phone tools › Calls, as for a Pebble.",
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textTertiary],
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}

/** One field per prompt, each removable, and room to add more up to what the watch keeps. */
@Composable
private fun PromptsEditor(prompts: List<String>, onChange: (List<String>) -> Unit) {
    val fields = remember { mutableStateListOf(*prompts.map(::TextFieldState).toTypedArray()) }
    LaunchedEffect(fields) {
        // The first value is the one the page opened with; every change after it is saved.
        snapshotFlow { fields.map { it.text.toString() } }.drop(1).collect(onChange)
    }
    Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEachIndexed { index, field ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(field, modifier = Modifier.weight(1f), placeholder = "Something to ask")
                IconButton(Lucide.Trash2, contentDescription = "Remove this prompt", onClick = { fields.removeAt(index) })
            }
        }
    }
    if (fields.size < PebbleLink.MAX_PROMPTS) {
        ActionRow("Add a prompt", Lucide.Plus) { fields.add(TextFieldState()) }
    }
}

/** The Settings row's line: whether the Pebble is on, and answers there. */
internal fun connectedDevicesSummary(settings: AppSettings): String = when {
    !settings.pebble -> "Pebble off."
    settings.pebbleAnswers -> "Pebble on, answering approvals and questions."
    else -> "Pebble on."
}

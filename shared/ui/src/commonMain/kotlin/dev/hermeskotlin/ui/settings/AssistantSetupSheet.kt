package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.bots.ASSISTANT_OPTIONAL_TOOLSET
import dev.hermeskotlin.core.bots.ASSISTANT_PROFILE
import dev.hermeskotlin.core.bots.ASSISTANT_TOOLSETS
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotDraft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.assistant_setup_create_new
import dev.hermeskotlin.ui.resources.assistant_setup_loading_profiles
import dev.hermeskotlin.ui.resources.assistant_setup_no_profiles
import dev.hermeskotlin.ui.resources.assistant_setup_tools_required
import dev.hermeskotlin.ui.resources.assistant_setup_use_existing
import dev.hermeskotlin.ui.resources.assistant_setup_use_existing_detail
import dev.hermeskotlin.ui.resources.assistant_setup_use_profile
import org.jetbrains.compose.resources.stringResource

/** What each toolset is called in setup, in the order the switches show. */
private val TOOLSET_LABELS = linkedMapOf(
    "web" to ("Web search" to "Look things up on the web and read pages."),
    "memory" to ("Memory" to "What the assistant remembers about you."),
    "session_search" to ("Past chats" to "Find things said in earlier conversations."),
    "todo" to ("To-do list" to "Keep track of things you ask it to remember to do."),
    "clarify" to ("Questions" to "Ask you a question when it needs one."),
    "skills" to ("Skills" to "Use the skills the profile has."),
    "vision" to ("Screen pictures" to "Look at pictures, including what's on your screen."),
    ASSISTANT_OPTIONAL_TOOLSET to ("Reminders and scheduled jobs" to "Let it set reminders and jobs that run later. Off by default: a voice request shouldn't schedule unattended work."),
)

/** The two ways the sheet sets up the assistant. */
private enum class SetupMode { UseExisting, CreateNew }

/**
 * Setup for the voice assistant. Create new makes a profile on the gateway (the default's model, a spoken-prose
 * SOUL, only the toolsets picked) and the caller records it in Settings. Use existing points the assistant at a
 * profile the gateway already has: the caller only records it in Settings, nothing is written on the gateway, so
 * that profile keeps its model, SOUL, skills and toolsets exactly as they are.
 */
@Composable
internal fun AssistantSetupSheet(
    /** Every profile the gateway reports (the Bots roster), offered by Use existing. */
    profiles: List<Bot>,
    /** The roster hasn't finished loading yet; an empty [profiles] doesn't mean there are none. */
    profilesLoading: Boolean = false,
    /** The profile the assistant already runs on; the sheet opens ready to change it. */
    current: String?,
    /** Profile names already on the gateway, which a new one can't take. */
    taken: Set<String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onUse: (profile: String) -> Unit,
    onMake: (profile: String, toolsets: Set<String>) -> Unit,
) {
    val labels = mapOf(
        SetupMode.UseExisting to stringResource(Res.string.assistant_setup_use_existing),
        SetupMode.CreateNew to stringResource(Res.string.assistant_setup_create_new),
    )
    // Already on, the likeliest reason for opening is changing the profile; with no roster yet there's nothing to pick.
    // Until the user touches the control the mode follows the data, so a sheet that opened before the roster landed
    // still lands on Use existing once profiles arrive.
    val chosen = remember { mutableStateOf<SetupMode?>(null) }
    val mode = chosen.value ?: if (current != null && profiles.isNotEmpty()) SetupMode.UseExisting else SetupMode.CreateNew
    // Same laziness for the pick: until the user taps a row it follows the current mapping, so it
    // resolves once the roster lands instead of being frozen at whatever the sheet opened with.
    val pickedChoice = remember { mutableStateOf<String?>(null) }
    val picked = pickedChoice.value ?: current?.takeIf { name -> profiles.any { it.name == name } }
    BottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Voice assistant", style = Theme[typography][title], color = Theme[colors][text])
            SegmentedControl(
                options = SetupMode.entries,
                selected = mode,
                onSelect = { chosen.value = it },
                optionLabel = { labels.getValue(it) },
                // While a create is in flight, switching to Use existing would have the finished create
                // overwrite the pick this sheet just recorded.
                enabled = !busy,
            )
            if (mode == SetupMode.UseExisting) {
                Text(
                    stringResource(Res.string.assistant_setup_use_existing_detail),
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                )
                if (profilesLoading && profiles.isEmpty()) {
                    Text(
                        stringResource(Res.string.assistant_setup_loading_profiles),
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textTertiary],
                    )
                } else if (profiles.isEmpty()) {
                    Text(
                        stringResource(Res.string.assistant_setup_no_profiles),
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][text],
                    )
                } else {
                    Column(Modifier.selectableGroup()) {
                        profiles.forEach { bot ->
                            val selected = picked == bot.name
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { pickedChoice.value = bot.name },
                                    )
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        bot.label,
                                        style = Theme[typography][body],
                                        color = if (selected) Theme[colors][accentText] else Theme[colors][text],
                                    )
                                    Text(bot.name, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
                                }
                                if (selected) {
                                    UnstyledIcon(
                                        Lucide.Check,
                                        contentDescription = null,
                                        tint = Theme[colors][accentText],
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        text = stringResource(Res.string.assistant_setup_use_profile),
                        onClick = { picked?.let(onUse) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = picked != null,
                    )
                }
            } else {
                CreateAssistantForm(taken = taken, busy = busy, error = error, onMake = onMake)
            }
        }
    }
}

/** The original create path: the profile's name, the toolsets it may use, and the Make button. */
@Composable
private fun CreateAssistantForm(
    taken: Set<String>,
    busy: Boolean,
    error: String?,
    onMake: (profile: String, toolsets: Set<String>) -> Unit,
) {
    val name = rememberTextFieldState(ASSISTANT_PROFILE)
    val toolsets = remember { mutableStateOf((ASSISTANT_TOOLSETS).toSet()) }
    val problem = BotDraft(profile = name.text.toString().trim()).problem(taken)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Makes a bot for the assistant, with a short spoken-style SOUL and the tools below. It uses the same model as a new bot. You can change any of it later in Bots.",
            style = Theme[typography][bodySmall],
            color = Theme[colors][textTertiary],
        )
        TextField(
            state = name,
            modifier = Modifier.fillMaxWidth(),
            label = "Profile name",
            supportingText = "Letters, digits, - and _.",
            error = problem?.takeIf { name.text.isNotEmpty() },
            enabled = !busy,
        )
        Text("Tools", style = Theme[typography][body], color = Theme[colors][text])
        TOOLSET_LABELS.forEach { (id, label) ->
            val (title, detail) = label
            SwitchRow(
                title = title,
                detail = detail,
                checked = id in toolsets.value,
                onCheckedChange = { checked ->
                    toolsets.value = if (checked) toolsets.value + id else toolsets.value - id
                },
            )
        }
        if (error != null) Text(error, style = Theme[typography][bodySmall], color = Theme[colors][text])
        // An empty pin clears the toolset pin on the gateway (the full default set applies), so an
        // all-off selection is refused here rather than sent: no tools is not a choice the user made.
        if (toolsets.value.isEmpty()) {
            Text(
                stringResource(Res.string.assistant_setup_tools_required),
                style = Theme[typography][bodySmall],
                color = Theme[colors][text],
            )
        }
        Button(
            text = if (busy) "Making it…" else "Make assistant",
            onClick = { onMake(name.text.toString().trim(), toolsets.value) },
            modifier = Modifier.fillMaxWidth(),
            enabled = problem == null && !busy && toolsets.value.isNotEmpty(),
            loading = busy,
        )
    }
}

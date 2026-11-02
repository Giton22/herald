package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquarePlus
import com.composables.icons.lucide.MessagesSquare
import com.composeunstyled.UnstyledIcon
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.ui.bots.BotAvatar
import dev.hermeskotlin.ui.bots.BotFaces
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.Profile
import dev.hermeskotlin.core.sessions.SessionPage
import dev.hermeskotlin.core.settings.CallTarget
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Spinner

/** A bounded, paged picker using the same profile and conversation APIs as Herald's sidebars. */
@Composable
internal fun CallTargetPicker(
    gateway: SavedGateway,
    viewModel: SettingsViewModel,
    faces: BotFaces,
    onDismiss: () -> Unit,
    onSelect: (CallTarget) -> Unit,
) {
    val prefs by viewModel.settings.collectAsStateWithLifecycle()
    val selected = (prefs.callTargets[gateway.url] ?: CallTarget()).takeIf { it.valid }
    var mode by remember(gateway.url) { mutableStateOf(0) }
    var offset by remember(gateway.url) { mutableStateOf(0) }
    var profiles by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var page by remember { mutableStateOf(SessionPage()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(gateway.url, mode, offset, retry) {
        if (mode == 0) return@LaunchedEffect
        loading = true
        error = null
        if (mode == 2) {
            val result = viewModel.callProfiles(gateway)
            if (result is ApiResult.Success) profiles = result.value.profiles.filterNot { it.isDefault }
            else error = result.errorMessage
        } else {
            val result = viewModel.callSessions(gateway, offset)
            if (result is ApiResult.Success) page = result.value else error = result.errorMessage
        }
        loading = false
    }
    BottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Default Call Target", modifier = Modifier.weight(1f), style = Theme[typography][title], color = Theme[colors][text])
                if (mode != 0) Button("Back", variant = ButtonVariant.Ghost, onClick = { mode = 0; offset = 0 })
            }
            when {
                mode == 0 -> {
                    CallTargetCard("New session", "Start a fresh Hermes conversation", selected != null && selected.profile == null && selected.sessionId == null, { onSelect(CallTarget()) }) {
                        UnstyledIcon(Lucide.MessageSquarePlus, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(32.dp))
                    }
                    CallTargetCard("Existing session", "Continue a saved conversation", selected?.sessionId != null, { mode = 1 }) {
                        UnstyledIcon(Lucide.MessagesSquare, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(32.dp))
                    }
                    CallTargetCard("Call a bot", "Choose a bot to call", selected?.profile != null, { mode = 2 }) {
                        BotAvatar(Bot(name = Bot.DEFAULT), null, size = 40.dp)
                    }
                }
                loading -> Spinner()
                error != null -> {
                    Text(error.orEmpty(), color = Theme[colors][text])
                    Button("Try again", onClick = { retry++ })
                }
                mode == 2 -> {
                    if (profiles.isEmpty()) Text("No bots available.", color = Theme[colors][text])
                    profiles.forEach { profile ->
                        val bot = faces.bots.firstOrNull { it.name == profile.name } ?: Bot(name = profile.name, displayName = profile.label)
                        CallTargetCard(bot.label, null, selected?.profile == bot.name, {
                            onSelect(CallTarget(profile = bot.name, label = bot.label))
                        }) { BotAvatar(bot, faces.picture(bot), size = 40.dp) }
                    }
                }
                else -> {
                    if (page.sessions.isEmpty()) Text("No sessions available.", color = Theme[colors][text])
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (offset > 0) Button("Previous", variant = ButtonVariant.Ghost, onClick = { offset = (offset - 50).coerceAtLeast(0) })
                        // Pinned rows can be backfilled outside the requested 50-session window.
                        if (offset + 50 < page.total) Button("Next", variant = ButtonVariant.Ghost, onClick = { offset += 50 })
                    }
                    page.sessions.forEach { session ->
                        CallTargetCard(session.displayTitle, session.preview?.takeIf { it.isNotBlank() } ?: "Continue this session", selected?.sessionId == session.id, {
                            onSelect(CallTarget(sessionId = session.id, label = session.displayTitle))
                        }) { UnstyledIcon(Lucide.MessagesSquare, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(32.dp)) }
                    }

                }
            }
        }
    }
}

/** Same rounded surfaces and selected wash as Herald's bot cards; the entire row is selectable. */
@Composable
private fun CallTargetCard(title: String, detail: String?, selected: Boolean, onClick: () -> Unit, leading: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Surface(
        Modifier.fillMaxWidth().clip(shape).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        color = Theme[colors][if (selected) accentSoft else surface],
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = Theme[typography][body], fontWeight = FontWeight.SemiBold, color = Theme[colors][text], maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (selected) UnstyledIcon(Lucide.Check, contentDescription = "Selected", tint = Theme[colors][accentText], modifier = Modifier.size(20.dp))
        }
    }
}

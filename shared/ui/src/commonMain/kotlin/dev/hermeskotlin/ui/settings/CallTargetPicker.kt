package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.input.ImeAction
import com.composables.icons.lucide.Search
import dev.hermeskotlin.designsystem.components.TextField
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
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
import dev.hermeskotlin.ui.components.ProvideAppWords
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** A bounded, paged picker using the same profile and conversation APIs as Herald's sidebars. */
@Composable
internal fun CallTargetPicker(
    gateway: SavedGateway,
    viewModel: SettingsViewModel,
    faces: BotFaces,
    onDismiss: () -> Unit,
    assistant: Boolean = false,
    profile: String? = null,
    onSelect: (CallTarget, String?) -> Unit,
) {
    val prefs by viewModel.settings.collectAsStateWithLifecycle()
    var userId by remember(gateway.url, profile, assistant) { mutableStateOf<String?>(null) }
    val selected = if (assistant) prefs.assistantTargets[gateway.url]?.get(profile.orEmpty())
        ?.takeIf { it.userId == userId }?.let { CallTarget(sessionId = it.sessionId, label = it.label) } ?: CallTarget()
        else (prefs.callTargets[gateway.url] ?: CallTarget()).takeIf { it.valid }
    var mode by remember(gateway.url, profile, assistant) { mutableStateOf(0) }
    val query = remember(gateway.url, profile, assistant, mode) { TextFieldState() }
    val search = query.text.toString().trim()
    var offset by remember(gateway.url, profile, assistant, search) { mutableStateOf(0) }
    var profiles by remember(gateway.url, profile, assistant) { mutableStateOf<List<Profile>>(emptyList()) }
    var page by remember(gateway.url, profile, assistant) { mutableStateOf(SessionPage()) }
    var retry by remember { mutableStateOf(0) }
    var loading by remember(gateway.url, profile, assistant, mode, offset, search, retry) { mutableStateOf(mode != 0) }
    var error by remember(gateway.url, profile, assistant, mode, offset, search, retry) { mutableStateOf<String?>(null) }
    val accountError = stringResource(Res.string.assistant_target_account_error)
    LaunchedEffect(gateway.url, profile, assistant, mode, offset, search, retry) {
        if (mode == 0) {
            if (assistant) {
                val user = viewModel.assistantUser(gateway)
                currentCoroutineContext().ensureActive()
                userId = user
            }
            return@LaunchedEffect
        }
        loading = true
        error = null
        if (mode == 2) {
            val result = viewModel.callProfiles(gateway)
            currentCoroutineContext().ensureActive()
            if (result is ApiResult.Success) profiles = result.value.profiles.filterNot { it.isDefault }
            else error = result.errorMessage
        } else {
            if (search.isNotEmpty()) delay(300)
            if (assistant) {
                val user = viewModel.assistantUser(gateway)
                currentCoroutineContext().ensureActive()
                userId = user
            }
            if (assistant && userId == null) error = accountError
            else {
                val result = viewModel.callSessions(gateway, offset, if (assistant) profile else null, search)
                currentCoroutineContext().ensureActive()
                val sameAccount = !assistant || viewModel.assistantUser(gateway) == userId
                currentCoroutineContext().ensureActive()
                if (!sameAccount) error = accountError
                else if (result is ApiResult.Success) page = result.value else error = result.errorMessage
            }
        }
        loading = false
    }
    BottomSheet(visible = true, onDismiss = onDismiss) {
      ProvideAppWords {
        Column(
            Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (assistant) Res.string.settings_assistant_target else Res.string.call_target_title), modifier = Modifier.weight(1f), style = Theme[typography][title], color = Theme[colors][text])
                if (mode != 0) Button(stringResource(Res.string.common_back), variant = ButtonVariant.Ghost, onClick = { mode = 0; offset = 0 })
            }
            if (mode == 1) TextField(
                state = query,
                placeholder = stringResource(Res.string.call_target_search_placeholder),
                supportingText = stringResource(Res.string.call_target_search_detail),
                leadingIcon = Lucide.Search,
                clearable = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            when {
                mode == 0 -> {
                    CallTargetCard(stringResource(if (assistant) Res.string.assistant_target_new else Res.string.call_target_new_session), stringResource(if (assistant) Res.string.assistant_target_new_detail else Res.string.call_target_new_session_detail), selected != null && selected.profile == null && selected.sessionId == null, { onSelect(CallTarget(), userId) }) {
                        UnstyledIcon(Lucide.MessageSquarePlus, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(32.dp))
                    }
                    CallTargetCard(stringResource(Res.string.call_target_existing), stringResource(Res.string.call_target_existing_detail), selected?.sessionId != null, { mode = 1 }) {
                        UnstyledIcon(Lucide.MessagesSquare, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(32.dp))
                    }
                    if (!assistant) CallTargetCard(stringResource(Res.string.call_target_bot), stringResource(Res.string.call_target_bot_detail), selected?.profile != null, { mode = 2 }) {
                        BotAvatar(Bot(name = Bot.DEFAULT), null, size = 40.dp)
                    }
                }
                loading -> Spinner()
                error != null -> {
                    Text(error.orEmpty(), color = Theme[colors][text])
                    Button(stringResource(Res.string.call_target_try_again), onClick = { retry++ })
                }
                mode == 2 -> {
                    if (profiles.isEmpty()) Text(stringResource(Res.string.call_target_no_bots), color = Theme[colors][text])
                    profiles.forEach { profile ->
                        val bot = faces.bots.firstOrNull { it.name == profile.name } ?: Bot(name = profile.name, displayName = profile.label)
                        CallTargetCard(bot.label, null, selected?.profile == bot.name, {
                            onSelect(CallTarget(profile = bot.name, label = bot.label), null)
                        }) { BotAvatar(bot, faces.picture(bot), size = 40.dp) }
                    }
                }
                else -> {
                    if (page.sessions.isEmpty()) Text(stringResource(if (search.isEmpty()) Res.string.call_target_no_sessions else Res.string.call_target_no_matches), color = Theme[colors][text])
                    if (search.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (offset > 0) Button(stringResource(Res.string.call_target_previous), variant = ButtonVariant.Ghost, onClick = { offset = (offset - 50).coerceAtLeast(0) })
                        // Pinned rows can be backfilled outside the requested 50-session window.
                        if (offset + 50 < page.total) Button(stringResource(Res.string.call_target_next), variant = ButtonVariant.Ghost, onClick = { offset += 50 })
                    }
                    page.sessions.forEach { session ->
                        CallTargetCard(session.displayTitle, (if (search.isNotEmpty()) session.snippet else session.preview)?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.call_target_continue), selected?.sessionId == session.id, {
                            onSelect(CallTarget(sessionId = session.id, label = session.displayTitle), userId)
                        }) { UnstyledIcon(Lucide.MessagesSquare, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(32.dp)) }
                    }

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
            if (selected) UnstyledIcon(Lucide.Check, contentDescription = stringResource(Res.string.call_target_selected), tint = Theme[colors][accentText], modifier = Modifier.size(20.dp))
        }
    }
}

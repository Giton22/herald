package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.rememberLanguageChoice
import dev.hermeskotlin.ui.resources.*
import dev.hermeskotlin.ui.sessions.SubpageHeader
import org.jetbrains.compose.resources.stringResource

/** Settings › Language: System default, then every language the app ships, each named in itself. A tap applies it. */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val choice = rememberLanguageChoice()
    PlatformBackHandler(enabled = true, onBack = onBack)
    LanguageView(picked = choice.picked, system = choice.system, onBack = onBack, onPick = choice::pick)
}

/** The page itself, stateless so previews and tests can draw it. */
@Composable
internal fun LanguageView(picked: String?, system: AppLanguage, onBack: () -> Unit, onPick: (String?) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader(stringResource(Res.string.settings_language), onBack = onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(Modifier.fillMaxWidth()) {
                    Column(Modifier.selectableGroup()) {
                        LanguageRow(
                            title = stringResource(Res.string.settings_language_system),
                            detail = stringResource(Res.string.settings_language_system_detail, system.endonym),
                            tag = null,
                            selected = picked == null,
                            onClick = { onPick(null) },
                        )
                        AppLanguages.forEach { language ->
                            Divider()
                            LanguageRow(language.endonym, detail = null, tag = language.tag, selected = picked == language.tag, onClick = { onPick(language.tag) })
                        }
                    }
                }
                Text(
                    stringResource(Res.string.settings_language_hint),
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** One choice: its name ([tag]'s language reads it, so a screen reader says it right), a check when picked. */
@Composable
private fun LanguageRow(title: String, detail: String?, tag: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .handCursor()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val style = Theme[typography][body].let { if (tag != null) it.copy(localeList = LocaleList(tag)) else it }
            Text(title, style = style, color = if (selected) Theme[colors][accentText] else Theme[colors][text], maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
        if (selected) UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
    }
}

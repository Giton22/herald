package dev.hermeskotlin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.AppWindow
import com.composables.icons.lucide.Blocks
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Keyboard
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.SquareTerminal
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.SunMoon
import com.composables.icons.lucide.TextSearch
import com.composables.icons.lucide.Wrench
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.PaletteItem
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** What the command palette can reach from the signed-in home. A null action leaves its row out. */
internal class HomeCommands(
    val newChat: () -> Unit,
    val settings: () -> Unit,
    val gateways: () -> Unit,
    val openChat: (SessionSummary) -> Unit,
    val openBot: (Bot) -> Unit,
    val chatMenu: (() -> Unit)?,
    val usage: (() -> Unit)?,
    val processes: (() -> Unit)?,
    val checkpoints: (() -> Unit)?,
    val theme: (ThemeMode) -> Unit,
    /** Hands a shortcut's action on, as its keys would. */
    val fire: (Shortcut) -> Unit,
)

/**
 * The palette's rows: chats and bots to open, the pages, what the open chat offers, the look, and help. The
 * keywords stay English: they're never shown, and only widen what a search finds beside the labels.
 */
@Composable
internal fun paletteItems(chats: List<SessionSummary>, bots: List<Bot>, commands: HomeCommands): List<PaletteItem> {
    val go = stringResource(Res.string.app_palette_go_to)
    val chat = stringResource(Res.string.app_palette_this_chat)
    val pinnedGroup = stringResource(Res.string.app_palette_pinned)
    val chatsGroup = stringResource(Res.string.app_page_chats)
    val botsGroup = stringResource(Res.string.app_page_bots)
    val look = stringResource(Res.string.settings_section_appearance)
    val help = stringResource(Res.string.app_palette_help)
    val newChat = stringResource(Res.string.app_new_chat)
    val searchChats = stringResource(Res.string.app_search_chats)
    val pages = PAGE_ICONS.map { (page, icon) -> Triple(page, icon, stringResource(page.label)) }
    val settings = stringResource(Res.string.common_settings)
    val gateways = stringResource(Res.string.gateways_title)
    val pickModel = stringResource(Res.string.app_pick_model)
    val findInChat = stringResource(Res.string.app_find_in_chat)
    val newWindow = stringResource(Res.string.app_new_window)
    val chatOptions = stringResource(Res.string.app_chat_options)
    val usage = stringResource(Res.string.app_usage)
    val processes = stringResource(Res.string.app_processes)
    val checkpoints = stringResource(Res.string.app_checkpoints)
    val light = stringResource(Res.string.app_theme_light)
    val dark = stringResource(Res.string.app_theme_dark)
    val system = stringResource(Res.string.app_theme_system)
    val shortcuts = stringResource(Res.string.app_keyboard_shortcuts)
    return buildList {
        add(PaletteItem(go, newChat, Lucide.SquarePen, keysLabel("Ctrl+N"), "start", commands.newChat))
        add(PaletteItem(go, searchChats, Lucide.Search, keysLabel("Ctrl+Shift+F"), "find history") { commands.fire(Shortcut.SearchChats) })
        for ((page, icon, label) in pages) {
            // The Inbox goes by a phone's push registration, which a desktop has none of.
            if (page == SidebarPage.Inbox && isDesktop) continue
            add(PaletteItem(go, label, icon, keywords = "sidebar page") { commands.fire(Shortcut.Go(page)) })
        }
        add(PaletteItem(go, settings, Lucide.Settings, keysLabel("Ctrl+,"), "preferences options", commands.settings))
        add(PaletteItem(go, gateways, Lucide.Globe, keywords = "connection server switch", run = commands.gateways))

        add(PaletteItem(chat, pickModel, Lucide.Sparkles, keysLabel("Ctrl+Shift+M"), "model reasoning effort") { commands.fire(Shortcut.ModelPicker) })
        // A new chat has nothing to find and nothing to pop out yet.
        if (commands.chatMenu != null) {
            add(PaletteItem(chat, findInChat, Lucide.TextSearch, keysLabel("Ctrl+F"), "search messages") { commands.fire(Shortcut.FindInChat) })
            add(PaletteItem(chat, newWindow, Lucide.AppWindow, keysLabel("Ctrl+Shift+N"), "pop out") { commands.fire(Shortcut.NewWindow) })
        }
        commands.chatMenu?.let { add(PaletteItem(chat, chatOptions, Lucide.Ellipsis, keywords = "rename delete export share menu", run = it)) }
        commands.usage?.let { add(PaletteItem(chat, usage, Lucide.Gauge, keywords = "tokens cost context", run = it)) }
        commands.processes?.let { add(PaletteItem(chat, processes, Lucide.SquareTerminal, keywords = "background jobs", run = it)) }
        commands.checkpoints?.let { add(PaletteItem(chat, checkpoints, Lucide.History, keywords = "rollback restore files", run = it)) }

        val pinned = chats.filter { it.pinned }
        pinned.forEach { add(PaletteItem(pinnedGroup, it.displayTitle, Lucide.Pin, keywords = "chat") { commands.openChat(it) }) }
        chats.filterNot { it.pinned }.forEach { add(PaletteItem(chatsGroup, it.displayTitle, Lucide.MessageSquare, keywords = "chat") { commands.openChat(it) }) }
        bots.filterNot { it.meta.hidden }.forEach { add(PaletteItem(botsGroup, it.label, Lucide.Bot, keywords = "bot ${it.name}") { commands.openBot(it) }) }

        add(PaletteItem(look, light, Lucide.Sun, keywords = "mode") { commands.theme(ThemeMode.Light) })
        add(PaletteItem(look, dark, Lucide.Moon, keywords = "mode") { commands.theme(ThemeMode.Dark) })
        add(PaletteItem(look, system, Lucide.SunMoon, keywords = "mode auto") { commands.theme(ThemeMode.System) })

        add(PaletteItem(help, shortcuts, Lucide.Keyboard, keysLabel("Ctrl+/"), "keys hotkeys") { commands.fire(Shortcut.ShortcutsHelp) })
    }
}

private val PAGE_ICONS = listOf(
    SidebarPage.Chats to Lucide.MessagesSquare,
    SidebarPage.Bots to Lucide.Bot,
    SidebarPage.Inbox to Lucide.Inbox,
    SidebarPage.Scheduled to Lucide.CalendarClock,
    SidebarPage.Archived to Lucide.Archive,
    SidebarPage.Capabilities to Lucide.Wrench,
    SidebarPage.Config to Lucide.SlidersHorizontal,
    SidebarPage.Plugins to Lucide.Puzzle,
    SidebarPage.Insights to Lucide.ChartColumn,
)

/** Ctrl+/: every shortcut, by what it's for. */
@Composable
internal fun ShortcutsDialog(visible: Boolean, onDismiss: () -> Unit) {
    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.app_keyboard_shortcuts),
        actions = { Button(stringResource(Res.string.app_done), onClick = onDismiss, variant = ButtonVariant.Secondary) },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScrollWithBar(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for ((group, keys) in SHORTCUT_GROUPS) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(group), style = Theme[typography][caption], color = Theme[colors][textTertiary])
                    for ((what, combo) in keys) {
                        // A Mac keeps ⌥↑ / ⌥↓ for the caret, so the chats don't take them there.
                        if (macKeys && combo.startsWith("Alt+Up")) continue
                        Row(Modifier.fillMaxWidth()) {
                            Text(stringResource(what), style = Theme[typography][body], color = Theme[colors][textColor], modifier = Modifier.weight(1f))
                            Text(keysLabel(combo), style = Theme[typography][body], color = Theme[colors][textTertiary])
                        }
                    }
                }
            }
        }
    }
}

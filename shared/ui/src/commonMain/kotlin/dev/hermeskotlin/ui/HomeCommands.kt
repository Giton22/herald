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

/** The palette's rows: chats and bots to open, the pages, what the open chat offers, the look, and help. */
internal fun paletteItems(chats: List<SessionSummary>, bots: List<Bot>, commands: HomeCommands): List<PaletteItem> = buildList {
    val go = "Go to"
    add(PaletteItem(go, "New chat", Lucide.SquarePen, keysLabel("Ctrl+N"), "start", commands.newChat))
    add(PaletteItem(go, "Search chats", Lucide.Search, keysLabel("Ctrl+Shift+F"), "find history") { commands.fire(Shortcut.SearchChats) })
    for ((page, icon) in PAGE_ICONS) {
        // The Inbox goes by a phone's push registration, which a desktop has none of.
        if (page == SidebarPage.Inbox && isDesktop) continue
        add(PaletteItem(go, page.label, icon, keywords = "sidebar page") { commands.fire(Shortcut.Go(page)) })
    }
    add(PaletteItem(go, "Settings", Lucide.Settings, keysLabel("Ctrl+,"), "preferences options", commands.settings))
    add(PaletteItem(go, "Gateways", Lucide.Globe, keywords = "connection server switch", run = commands.gateways))

    val chat = "This chat"
    add(PaletteItem(chat, "Pick a model", Lucide.Sparkles, keysLabel("Ctrl+Shift+M"), "model reasoning effort") { commands.fire(Shortcut.ModelPicker) })
    // A new chat has nothing to find and nothing to pop out yet.
    if (commands.chatMenu != null) {
        add(PaletteItem(chat, "Find in chat", Lucide.TextSearch, keysLabel("Ctrl+F"), "search messages") { commands.fire(Shortcut.FindInChat) })
        add(PaletteItem(chat, "Open in a new window", Lucide.AppWindow, keysLabel("Ctrl+Shift+N"), "pop out") { commands.fire(Shortcut.NewWindow) })
    }
    commands.chatMenu?.let { add(PaletteItem(chat, "Chat options", Lucide.Ellipsis, keywords = "rename delete export share menu", run = it)) }
    commands.usage?.let { add(PaletteItem(chat, "Usage", Lucide.Gauge, keywords = "tokens cost context", run = it)) }
    commands.processes?.let { add(PaletteItem(chat, "Processes", Lucide.SquareTerminal, keywords = "background jobs", run = it)) }
    commands.checkpoints?.let { add(PaletteItem(chat, "Checkpoints", Lucide.History, keywords = "rollback restore files", run = it)) }

    val pinned = chats.filter { it.pinned }
    pinned.forEach { add(PaletteItem("Pinned", it.displayTitle, Lucide.Pin, keywords = "chat") { commands.openChat(it) }) }
    chats.filterNot { it.pinned }.forEach { add(PaletteItem("Chats", it.displayTitle, Lucide.MessageSquare, keywords = "chat") { commands.openChat(it) }) }
    bots.filterNot { it.meta.hidden }.forEach { add(PaletteItem("Bots", it.label, Lucide.Bot, keywords = "bot ${it.name}") { commands.openBot(it) }) }

    val look = "Appearance"
    add(PaletteItem(look, "Light theme", Lucide.Sun, keywords = "mode") { commands.theme(ThemeMode.Light) })
    add(PaletteItem(look, "Dark theme", Lucide.Moon, keywords = "mode") { commands.theme(ThemeMode.Dark) })
    add(PaletteItem(look, "System theme", Lucide.SunMoon, keywords = "mode auto") { commands.theme(ThemeMode.System) })

    add(PaletteItem("Help", "Keyboard shortcuts", Lucide.Keyboard, keysLabel("Ctrl+/"), "keys hotkeys") { commands.fire(Shortcut.ShortcutsHelp) })
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
        title = "Keyboard shortcuts",
        actions = { Button("Done", onClick = onDismiss, variant = ButtonVariant.Secondary) },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScrollWithBar(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for ((group, keys) in SHORTCUT_GROUPS) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(group, style = Theme[typography][caption], color = Theme[colors][textTertiary])
                    for ((what, combo) in keys) {
                        // A Mac keeps ⌥↑ / ⌥↓ for the caret, so the chats don't take them there.
                        if (macKeys && combo.startsWith("Alt+Up")) continue
                        Row(Modifier.fillMaxWidth()) {
                            Text(what, style = Theme[typography][body], color = Theme[colors][textColor], modifier = Modifier.weight(1f))
                            Text(keysLabel(combo), style = Theme[typography][body], color = Theme[colors][textTertiary])
                        }
                    }
                }
            }
        }
    }
}

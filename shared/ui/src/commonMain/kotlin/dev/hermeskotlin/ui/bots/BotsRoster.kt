package dev.hermeskotlin.ui.bots

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BellRing
import com.composables.icons.lucide.CalendarClock
import dev.hermeskotlin.core.chat.Waiting
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import dev.hermeskotlin.designsystem.components.IconButton
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquarePlus
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.TriangleAlert
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotTrouble
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.core.rooms.DesktopRoom
import dev.hermeskotlin.core.rooms.RoomMember
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.bots.lastActivity
import dev.hermeskotlin.core.bots.rosterPreview
import dev.hermeskotlin.core.bots.showsPicture
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.MenuAction
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.SectionLabel
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.rooms.RoomAsk
import dev.hermeskotlin.ui.rooms.RoomAskDialogs
import dev.hermeskotlin.ui.rooms.RoomFaces
import dev.hermeskotlin.ui.rooms.roomMembers
import dev.hermeskotlin.ui.rooms.rowSubtitle
import dev.hermeskotlin.ui.rooms.RoomMenuActions
import dev.hermeskotlin.ui.sessions.ListNotice
import dev.hermeskotlin.ui.sessions.ListSpinner
import dev.hermeskotlin.ui.sessions.MessageBanner

/** What a roster row's long-press menu can ask for. */
interface BotActions {
    fun open(bot: Bot)
    fun setPinned(bot: Bot, pinned: Boolean)
    fun setHidden(bot: Bot, hidden: Boolean)

    /** Archive the bot's chat and begin an empty one. */
    fun startFresh(bot: Bot)

    /** The bot's newest conversation that isn't its Bot Chat (a routine run, a side thread). */
    fun openRecent(bot: Bot)

    /** A throwaway chat with the bot, apart from its permanent one. */
    fun newChat(bot: Bot)

    fun create()
    fun edit(bot: Bot)
    fun duplicate(bot: Bot)

    /** Delete the bot's profile for good; asked about first. */
    fun delete(bot: Bot)

    /** Ask the gateway again whether the bot can work, e.g. after fixing its keys elsewhere. */
    fun checkAgain(bot: Bot)

    /** The bot's scheduled routines. */
    fun routines(bot: Bot)
}

/**
 * The Bots side of the sidebar, Desktop's roster: one row per bot with its face, name, the latest line of
 * its chat and how long ago. Tapping a row opens the bot's one permanent chat; a long press offers the rest.
 */
@Composable
fun BotsRoster(
    state: BotsUiState,
    avatars: Map<String, ByteArray>,
    /** The stored session open in the chat pane; its bot's row is highlighted. */
    selectedId: String?,
    /** The open chat has a turn running, so its bot is thinking. */
    selectedRunning: Boolean,
    nowSeconds: Double,
    actions: BotActions,
    onRetry: () -> Unit,
    onDismissNotice: () -> Unit,
    /** Bots that can't work until something is fixed, by profile. */
    troubles: Map<String, BotTrouble> = emptyMap(),
    /** Each bot's routine whose last run went wrong, by profile. */
    failingRoutines: Map<String, CronJob> = emptyMap(),
    /** The bots needing the user, most pressing first. */
    needsYou: List<NeedsYou> = emptyList(),
    /** The gateway's hosted rooms; the section is drawn only when the gateway hosts them. */
    rooms: List<Room> = emptyList(),
    /** Whether this gateway hosts rooms at all (`groups.capabilities`). */
    roomsAvailable: Boolean = false,
    /** The rooms with lines the user hasn't seen, by id. */
    unreadRooms: Set<String> = emptySet(),
    /** Opens a room's conversation. */
    onOpenRoom: (Room) -> Unit = {},
    /** Opens a Desktop room's mirrored copy; it stays read-only here. */
    onOpenDesktopRoom: (DesktopRoom) -> Unit = {},
    /** Starts a new room: name it and pick its bots. */
    onNewRoom: () -> Unit = {},
    onRenameRoom: (Room, String) -> Unit = { _, _ -> },
    onDeleteRoom: (Room) -> Unit = {},
    /** Why a room's rename or delete didn't work. */
    roomsNotice: String? = null,
    onDismissRoomsNotice: () -> Unit = {},
) {
    val heldUp = remember(needsYou) { needsYou.filterIsInstance<NeedsYou.Answer>().associate { it.bot.name to it.waiting } }
    var roomAsk by remember { mutableStateOf<RoomAsk?>(null) }
    RoomAskDialogs(roomAsk, onDismiss = { roomAsk = null }, onRename = onRenameRoom, onDelete = onDeleteRoom)
    var hiddenOpen by remember { mutableStateOf(false) }
    var startOver by remember { mutableStateOf<Bot?>(null) }
    var deleting by remember { mutableStateOf<Bot?>(null) }
    // Two bots that read the same get their @handles, like Desktop's roster.
    val sameName = remember(state.all) { state.all.groupBy { it.label.lowercase() }.filterValues { it.size > 1 }.keys }
    // A room's members are drawn with the same faces as their bots' rows.
    val roomFaces = remember(state.all, avatars) { BotFaces(state.all, avatars) }
    val desktopRooms = state.desktopRooms
    val row: @Composable (Bot, Boolean) -> Unit = { bot, hidden ->
        val chat = bot.canonicalSession
        val selected = selectedId != null && chat != null && (selectedId == chat.id || selectedId == chat.openId)
        BotRow(
            bot = bot,
            picture = avatars[bot.name]?.takeIf { showsPicture(bot, it) },
            selected = selected,
            unread = bot.name in state.unread,
            thinking = selected && selectedRunning,
            working = bot.isWorking(nowSeconds),
            opening = state.opening == bot.name,
            hidden = hidden,
            showHandle = bot.label.lowercase() in sameName,
            trouble = troubles[bot.name],
            failingRoutine = failingRoutines[bot.name],
            waiting = heldUp[bot.name],
            actions = actions,
            onStartOver = { startOver = bot },
            onDelete = { deleting = bot },
        )
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Room to scroll the last rows out from under the floating footer.
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
        ) {
            if (needsYou.isNotEmpty()) {
                item(key = "needs-you-label") {
                    SectionLabel("Needs you · ${needsYou.size}", Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
                }
                // Row keys carry a ':', which no profile name has, so a bot named like a key can't collide.
                items(needsYou, key = { "needs:${it.bot.name}" }) { item ->
                    NeedsYouRow(
                        item,
                        picture = avatars[item.bot.name]?.takeIf { showsPicture(item.bot, it) },
                        // A failing routine is mended on its page; anything else in the bot's chat.
                        onClick = { if (item is NeedsYou.Routine) actions.routines(item.bot) else actions.open(item.bot) },
                    )
                }
            }
            // Hosted rooms when the gateway runs them, and Desktop's own read-only copies beside them.
            if (roomsAvailable || desktopRooms.isNotEmpty()) {
                item(key = "rooms-label") {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Rooms", Modifier.weight(1f))
                        // Only a gateway that hosts rooms can make one; Desktop's are made on Desktop.
                        if (roomsAvailable) IconButton(Lucide.Plus, contentDescription = "New room", onClick = onNewRoom)
                    }
                }
                if (roomsAvailable) {
                    roomsNotice?.let { message ->
                        item(key = "rooms-notice") { MessageBanner(message, onDismiss = onDismissRoomsNotice, modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                    if (rooms.isEmpty()) {
                        // With Desktop rooms below, the page does have rooms to show; only say "none" when there are none.
                        if (desktopRooms.isEmpty()) item(key = "rooms-empty") { ListNotice("No rooms yet. New room starts one with 2\u20136 bots.") }
                    } else {
                        items(rooms, key = { "room:${it.roomId}" }) { room ->
                            RoomRow(
                                room,
                                roomFaces,
                                unread = room.roomId in unreadRooms,
                                onClick = { onOpenRoom(room) },
                                onRename = { roomAsk = RoomAsk.Rename(room) },
                                onDelete = { roomAsk = RoomAsk.Delete(room) },
                            )
                        }
                    }
                }
                if (desktopRooms.isNotEmpty()) {
                    item(key = "desktop-rooms-label") {
                        SectionLabel("On Desktop", Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
                    }
                    items(desktopRooms, key = { "desktop-room:${it.key}" }) { room ->
                        DesktopRoomRow(room, roomFaces, onClick = { onOpenDesktopRoom(room) })
                    }
                }
            }
            item(key = "label") {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Bots", Modifier.weight(1f))
                    IconButton(Lucide.Plus, contentDescription = "New bot", onClick = actions::create)
                }
            }
            when {
                state.loading && state.all.isEmpty() -> item(key = "loading") { ListSpinner() }
                state.unsupported -> item(key = "unsupported") {
                    ListNotice("This gateway doesn't list bots yet. Bot Mode needs Hermes 0.20.3 or newer.")
                }
                state.error != null && state.all.isEmpty() -> item(key = "error") {
                    ListNotice("Couldn't load the bots. ${state.error}", action = "Try again", onAction = onRetry)
                }
                state.all.isEmpty() -> item(key = "empty") { ListNotice("No bots on this gateway yet.") }
                state.bots.isEmpty() -> item(key = "all-hidden") {
                    ListNotice("All bots are hidden. They keep working and keep their history.", action = "Show hidden bots") { hiddenOpen = true }
                }
            }
            items(state.bots, key = { "bot:${it.name}" }) { row(it, false) }
            if (state.hidden.isNotEmpty()) {
                item(key = "hidden-label") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinTouchTarget)
                            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
                            .clickable(onClickLabel = if (hiddenOpen) "Collapse hidden bots" else "Show hidden bots") { hiddenOpen = !hiddenOpen }
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UnstyledIcon(
                            if (hiddenOpen) Lucide.ChevronDown else Lucide.ChevronRight,
                            contentDescription = null,
                            tint = Theme[colors][textTertiary],
                            modifier = Modifier.size(14.dp),
                        )
                        Text("Hidden ${state.hidden.size}", style = Theme[typography][caption], color = Theme[colors][textTertiary])
                    }
                }
                if (hiddenOpen) items(state.hidden, key = { "hidden:${it.name}" }) { row(it, true) }
            }
        }
        state.notice?.let { message ->
            MessageBanner(message, onDismiss = onDismissNotice, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
        }
    }
    StartOverDialog(startOver, onDismiss = { startOver = null }, onConfirm = actions::startFresh)
    DeleteBotDialog(deleting, onDismiss = { deleting = null }, onConfirm = actions::delete)
}

@Composable
private fun DeleteBotDialog(bot: Bot?, onDismiss: () -> Unit, onConfirm: (Bot) -> Unit) {
    var shown by remember { mutableStateOf(bot) }
    if (bot != null) shown = bot
    val b = shown ?: return
    Dialog(
        visible = bot != null,
        onDismissRequest = onDismiss,
        title = "Delete ${b.label}?",
        message = "The ${b.name} profile is removed from the gateway with its chats, memory, skills and settings. " +
            "This can't be undone.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Delete", onClick = { onDismiss(); onConfirm(b) }, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotRow(
    bot: Bot,
    picture: ByteArray?,
    selected: Boolean,
    unread: Boolean,
    thinking: Boolean,
    working: Boolean,
    opening: Boolean,
    hidden: Boolean,
    showHandle: Boolean,
    trouble: BotTrouble?,
    failingRoutine: CronJob?,
    /** Its chat is held up on the user. */
    waiting: Waiting?,
    actions: BotActions,
    onStartOver: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val pinned = bot.meta.pinned
    // Only a conversation that isn't the Bot Chat is worth opening as "recent".
    val recent = bot.lastSession?.id?.takeIf { it != bot.canonicalSession?.id && it != bot.canonicalSession?.openId }
    DropdownMenu(
        expanded = menuOpen,
        onExpandedChange = { menuOpen = it },
        items = {
            fun act(block: () -> Unit) = { menuOpen = false; block() }
            if (trouble != null) MenuAction("Check again", Lucide.RefreshCw, act { actions.checkAgain(bot) })
            MenuAction(if (pinned) "Unpin" else "Pin to top", if (pinned) Lucide.PinOff else Lucide.Pin, act { actions.setPinned(bot, !pinned) })
            MenuAction(if (hidden) "Unhide" else "Hide", if (hidden) Lucide.Eye else Lucide.EyeOff, act { actions.setHidden(bot, !hidden) })
            if (recent != null) MenuAction("Open recent session", Lucide.History, act { actions.openRecent(bot) })
            MenuAction("New chat with ${bot.label}", Lucide.MessageSquarePlus, act { actions.newChat(bot) })
            MenuAction("Routines", Lucide.CalendarClock, act { actions.routines(bot) })
            if (bot.canonicalSession != null) MenuAction("Start fresh", Lucide.RotateCcw, act(onStartOver))
            MenuAction("Edit", Lucide.Pencil, act { actions.edit(bot) })
            MenuAction("Duplicate", Lucide.Copy, act { actions.duplicate(bot) })
            // The gateway keeps its primary profile.
            if (bot.name != Bot.DEFAULT) MenuAction("Delete", Lucide.Trash2, act(onDelete))
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clip(shape)
                .then(if (selected) Modifier.background(Theme[colors][accentSoft], shape) else Modifier)
                .combinedClickable(
                    onClick = { actions.open(bot) },
                    onClickLabel = "Open ${bot.label}'s chat",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                    onLongClickLabel = "${bot.label} actions",
                    interactionSource = null,
                    indication = rememberColoredIndication(Theme[colors][text]),
                )
                .alpha(if (hidden) 0.6f else 1f)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                BotAvatar(bot, picture)
                if (working || thinking) {
                    Box(Modifier.align(Alignment.BottomEnd).size(10.dp).background(Theme[colors][success], CircleShape))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (pinned) UnstyledIcon(Lucide.Pin, contentDescription = "Pinned", tint = Theme[colors][textTertiary], modifier = Modifier.size(12.dp))
                        Text(
                            bot.label,
                            style = Theme[typography][body].copy(fontSize = 15.sp, fontWeight = if (selected || unread) FontWeight.SemiBold else FontWeight.Medium),
                            color = Theme[colors][text],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (showHandle) {
                            Text("@${bot.name}", style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
                        }
                        if (unread) Box(Modifier.size(7.dp).background(Theme[colors][accent], CircleShape))
                        if (trouble != null || failingRoutine != null) {
                            UnstyledIcon(
                                Lucide.TriangleAlert,
                                contentDescription = "Needs attention",
                                tint = Theme[colors][warning],
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    when {
                        opening -> Spinner(Modifier.size(14.dp))
                        bot.lastActivity() != null -> Text(relativeTime(bot.lastActivity()), style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
                    }
                }
                val preview = bot.rosterPreview()
                val (line, italic) = when {
                    // Held up on the user beats everything: nothing moves until they answer.
                    waiting != null -> waiting.label to false
                    thinking -> "Thinking…" to false
                    working -> "Working…" to false
                    // What's wrong outranks the last line: a phone has no hover to say it.
                    trouble != null -> trouble.problem.hint to false
                    failingRoutine != null -> "Routine “${failingRoutine.displayName}” didn't go through" to false
                    preview?.fromBot != null -> "${preview.fromBot}: ${preview.text}" to true
                    preview != null -> preview.text to false
                    else -> (bot.description?.takeIf { it.isNotBlank() } ?: "Say hello") to false
                }
                Text(
                    line,
                    style = Theme[typography][bodySmall].copy(fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal),
                    color = when {
                        waiting != null -> Theme[colors][accent]
                        working || thinking -> Theme[colors][success]
                        trouble != null || failingRoutine != null -> Theme[colors][warning]
                        else -> Theme[colors][textSecondary]
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One bot needing the user: its face, name and what it needs; tapping goes where it can be dealt with. */
@Composable
private fun NeedsYouRow(item: NeedsYou, picture: ByteArray?, onClick: () -> Unit) {
    val urgent = item is NeedsYou.Answer
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
            .clickable(onClickLabel = if (item is NeedsYou.Routine) "Open ${item.bot.label}'s routines" else "Open ${item.bot.label}'s chat", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(item.bot, picture, size = 28.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(item.bot.label, style = Theme[typography][body], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.reason,
                style = Theme[typography][bodySmall],
                color = if (urgent) Theme[colors][accent] else Theme[colors][warning],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        UnstyledIcon(
            if (urgent) Lucide.BellRing else Lucide.TriangleAlert,
            contentDescription = null,
            tint = if (urgent) Theme[colors][accent] else Theme[colors][warning],
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Asks before starting [bot]'s chat over; shown while [bot] is set. */
@Composable
internal fun StartOverDialog(bot: Bot?, onDismiss: () -> Unit, onConfirm: (Bot) -> Unit) {
    var shown by remember { mutableStateOf(bot) }
    if (bot != null) shown = bot
    val b = shown ?: return
    Dialog(
        visible = bot != null,
        onDismissRequest = onDismiss,
        title = "Start fresh with ${b.label}?",
        message = "${b.label}'s chat is archived and a new, empty one begins. ${b.label} keeps its memory, skills " +
            "and settings, and the old chat stays on the gateway, archived.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Start fresh", onClick = { onDismiss(); onConfirm(b) }, variant = ButtonVariant.Primary, size = ButtonSize.Small)
        },
    )
}

/**
 * One hosted room: its members' faces, its name, who's in it, and when it last moved. A long press
 * opens its actions, as a bot's row does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomRow(room: Room, faces: BotFaces, unread: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = menuOpen,
        onExpandedChange = { menuOpen = it },
        items = {
            RoomMenuActions(
                onRename = { menuOpen = false; onRename() },
                onDelete = { menuOpen = false; onDelete() },
            )
        },
    ) {
        RoomRowContent(
            room,
            faces,
            unread,
            Modifier.combinedClickable(
                onClick = onClick,
                onClickLabel = "Open room ${room.name}",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
                onLongClickLabel = "${room.name} actions",
                interactionSource = null,
                indication = rememberColoredIndication(Theme[colors][text]),
            ),
        )
    }
}

@Composable
private fun RoomRowContent(room: Room, faces: BotFaces, unread: Boolean, clicks: Modifier) {
    // New lines since the user last looked: bold and a dot, as a bot's row shows them.
    RoomRowLayout(room.members, room.name, roomSubtitle(room), room.updatedAt, faces, marked = unread, clicks = clicks)
}

/** A room's row, hosted or Desktop's: faces, the name (bold with a dot when [marked]), a subtitle and its time. */
@Composable
private fun RoomRowLayout(
    members: List<RoomMember>,
    name: String,
    subtitle: String,
    updatedAt: Double?,
    faces: BotFaces,
    marked: Boolean,
    clicks: Modifier,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
            .then(clicks)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomFaces(members, faces, size = 26.dp, max = 3)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = Theme[typography][body].copy(fontSize = 15.sp, fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Medium),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (marked) Box(Modifier.size(7.dp).background(Theme[colors][accent], CircleShape))
            }
            Text(
                subtitle,
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val last = relativeTime(updatedAt)
        if (last.isNotBlank()) {
            Text(last, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
        }
    }
}

/** "Ops, Scribe, Cadence": the faces show how many, the names say who. */
private fun roomSubtitle(room: Room): String {
    if (room.members.isEmpty()) return "No members"
    return room.members.joinToString(", ") { it.label }
}

/**
 * One Desktop room: the mirror's copy, read-only — no room menu, since the room belongs to Desktop and
 * continuing happens there. Its newest line is the second line, and a dot marks one whose newest member
 * line asks for the user (`@user`), Desktop's own needs-you rule.
 */
@Composable
private fun DesktopRoomRow(room: DesktopRoom, faces: BotFaces, onClick: () -> Unit) {
    val members = remember(room.members) { room.roomMembers() }
    val subtitle = remember(room, faces) { room.rowSubtitle(faces) }
    RoomRowLayout(
        members,
        room.name,
        subtitle,
        // The mirror's times are epoch millis; rows read seconds like every other row.
        room.updatedAt?.div(1000.0),
        faces,
        marked = room.needsYou,
        clicks = Modifier.clickable(onClickLabel = "Open room ${room.name}") { onClick() },
    )
}

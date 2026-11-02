package dev.hermeskotlin.ui.rooms

import dev.hermeskotlin.designsystem.components.ScrollbarBox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.hermeskotlin.ui.chat.ComposerKey
import dev.hermeskotlin.ui.chat.composerKey
import dev.hermeskotlin.ui.chat.isPickKey
import dev.hermeskotlin.ui.chat.moveHighlight
import dev.hermeskotlin.ui.chat.suggestionStep
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.mentionQuery
import dev.hermeskotlin.core.rooms.mentionable
import dev.hermeskotlin.core.rooms.mentionsEveryone
import dev.hermeskotlin.core.rooms.roomRecipients
import dev.hermeskotlin.core.rooms.waitingLine
import dev.hermeskotlin.ui.chat.MentionChoice
import dev.hermeskotlin.ui.chat.MentionSuggestions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.AtSign
import com.composables.icons.lucide.Ellipsis
import dev.hermeskotlin.designsystem.stroke
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.theme.Theme
import androidx.compose.foundation.text.input.TextFieldState
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.chat.ShownAttachment
import dev.hermeskotlin.core.chat.AttachmentKind
import dev.hermeskotlin.core.rooms.RoomAttachment
import dev.hermeskotlin.ui.chat.rememberAttachmentPicker
import dev.hermeskotlin.ui.chat.AttachSheet
import dev.hermeskotlin.ui.chat.ComposerTray
import dev.hermeskotlin.ui.chat.SentAttachments
import dev.hermeskotlin.ui.LocalFocusHeld
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutHandler
import dev.hermeskotlin.ui.isDesktop
import com.composables.icons.lucide.Paperclip
import dev.hermeskotlin.core.chat.linkFileUris
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.core.rooms.RoomLine
import dev.hermeskotlin.core.rooms.RoomPendingAction
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.CopyButton
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.LocalFileLinks
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.onUserBubble
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.userBubble
import dev.hermeskotlin.designsystem.userBubbleStroke
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.ui.bots.BotFaces
import dev.hermeskotlin.ui.bots.LocalBotFaces
import dev.hermeskotlin.ui.chat.BarButton
import dev.hermeskotlin.ui.chat.MessageTimeLabel
import dev.hermeskotlin.ui.chat.FileLoad
import dev.hermeskotlin.ui.chat.FileDownload
import dev.hermeskotlin.ui.chat.fileDownload
import dev.hermeskotlin.ui.chat.fileLoad
import dev.hermeskotlin.ui.chat.SendButton
import dev.hermeskotlin.ui.chat.BarStatus
import dev.hermeskotlin.ui.chat.SendIcon
import dev.hermeskotlin.ui.chat.StatusTone
import dev.hermeskotlin.ui.chat.TopBar
import dev.hermeskotlin.ui.sessions.ListNotice
import dev.hermeskotlin.ui.sessions.ListSpinner
import dev.hermeskotlin.ui.sessions.MessageBanner
import org.koin.compose.viewmodel.koinViewModel

/**
 * One hosted room, open, laid out like a chat: the chat's top bar with the members' faces, the
 * transcript from the gateway's log with each bot in its own color and face, and the chat's composer.
 * The room keeps running on the gateway with nobody watching.
 */
@Composable
fun RoomScreen(
    viewModel: RoomsViewModel = koinViewModel(),
    onOpenSidebar: () -> Unit,
    onBack: () -> Unit,
    onOpenFile: (String?, GatewayFile) -> Unit = { _, _ -> },
    /** Off while a page covers the room (Settings, the bot editor, a plugin), so its keys don't type in it unseen. */
    keysEnabled: Boolean = true,
) {
    val open by viewModel.opened.collectAsStateWithLifecycle()
    val room = open ?: return
    RoomView(room, viewModel, LocalBotFaces.current, onOpenSidebar = onOpenSidebar, onBack = onBack, onOpenFile = onOpenFile, keysEnabled = keysEnabled)
}

/** What an open room can ask for; [RoomsViewModel] does it all, previews nothing. */
interface RoomActions {
    /** What the user is typing to the room. */
    val composer: TextFieldState
    fun addAttachments(picked: List<OutgoingAttachment>, draftKey: Int) {}
    fun removeAttachment(id: String) {}
    fun attachmentError(message: String, draftKey: Int) {}
    fun send()
    fun stop()
    fun loadEarlier()
    fun approve(action: RoomPendingAction, choice: String)
    fun retry(action: RoomPendingAction)
    fun renameRoom(room: Room, name: String)
    fun deleteRoom(room: Room)
    fun dismissRoomNotice()
}

/** An open room's layout, apart from the view model, so previews can draw it from sample data. */
@Composable
internal fun RoomView(
    room: OpenRoom,
    viewModel: RoomActions,
    faces: BotFaces,
    onOpenSidebar: () -> Unit,
    onBack: () -> Unit,
    onOpenFile: (String?, GatewayFile) -> Unit = { _, _ -> },
    keysEnabled: Boolean = true,
) {
    var ask by remember { mutableStateOf<RoomAsk?>(null) }
    RoomAskDialogs(ask, onDismiss = { ask = null }, onRename = viewModel::renameRoom, onDelete = viewModel::deleteRoom)
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize().imePadding()) {
            val members = room.room.members
            TopBar(
                title = room.room.name,
                titleFace = members.takeIf { it.isNotEmpty() }?.let { { RoomFaces(it, faces, size = 20.dp, max = 3) } },
                // Who's in it, unless the room waits on the user.
                status = when {
                    room.pendingActions.isNotEmpty() -> BarStatus("Waiting on you", StatusTone.Waiting)
                    members.isNotEmpty() -> BarStatus(members.joinToString(", ") { it.label }, StatusTone.Neutral)
                    else -> null
                },
                onOpenSidebar = onOpenSidebar,
                onNewChat = null,
                onOpenMenu = null,
                trailing = {
                    var menuOpen by remember { mutableStateOf(false) }
                    DropdownMenu(
                        expanded = menuOpen,
                        onExpandedChange = { menuOpen = it },
                        items = {
                            RoomMenuActions(
                                onRename = { menuOpen = false; ask = RoomAsk.Rename(room.room) },
                                onDelete = { menuOpen = false; ask = RoomAsk.Delete(room.room) },
                            )
                        },
                    ) {
                        BarButton(Lucide.Ellipsis, "Room options", onClick = { menuOpen = true })
                    }
                    BarButton(Lucide.X, "Close the room", onClick = onBack)
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Transcript(room, faces, onLoadEarlier = viewModel::loadEarlier, onOpenFile = onOpenFile)
            }
            // A failed read in full, above whatever waits on the user; the loop keeps trying.
            room.error?.let { ListNotice(it) }
            PendingActions(room, onApprove = viewModel::approve, onRetry = viewModel::retry)
            if (room.working && room.pendingActions.isEmpty()) {
                Row(
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spinner(Modifier.size(14.dp))
                    // Who's replying to the user's message; past its first round, bots answering each other.
                    Text(
                        waitingLine(room.waiting) ?: "The room is talking it over…",
                        style = Theme[typography][caption],
                        color = Theme[colors][textTertiary],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val mentions = rememberRoomMentions(room, viewModel, faces)
            RoomMentions(room, mentions)
            RoomComposer(room, viewModel, mentions, keysEnabled)
        }
        room.notice?.let { message ->
            MessageBanner(message, onDismiss = viewModel::dismissRoomNotice, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
        }
    }
}

/**
 * The transcript: messages, system lines, and nothing of the room's machinery. Reaching its top reads
 * the lines before it, while the log goes back further.
 */
@Composable
private fun Transcript(room: OpenRoom, faces: BotFaces, onLoadEarlier: () -> Unit, onOpenFile: (String?, GatewayFile) -> Unit) {
    val listState = rememberLazyListState()
    val lines = room.lines
    // A newer line brings the end of the conversation into view; older ones read in above don't move the reader.
    val newest = lines.lastOrNull()?.seq
    // Whether the first jump to the end is done: before it, the list starts at its top without the reader there.
    var atEndOnce by remember { mutableStateOf(false) }
    LaunchedEffect(newest) {
        if (newest == null) return@LaunchedEffect
        listState.animateScrollToItem(lines.size - 1)
        atEndOnce = true
    }
    // The line the reader is on when older lines are asked for, and how far down it sits, to put them back there.
    var anchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(room.loadingEarlier) {
        if (room.loadingEarlier) {
            anchor = lines.getOrNull(listState.firstVisibleItemIndex)?.let { it.seq to listState.firstVisibleItemScrollOffset }
        }
    }
    val oldest = lines.firstOrNull()?.seq
    LaunchedEffect(oldest) {
        val (seq, offset) = anchor ?: return@LaunchedEffect
        anchor = null
        val index = lines.indexOfFirst { it.seq == seq }
        if (index > 0) listState.scrollToItem(index, offset)
    }
    // At the top (or with nothing to show yet), read further back while there's more. Once older lines are
    // in, the reader is back on their line, off the top, so the next read waits for them to scroll up again.
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    LaunchedEffect(atTop, atEndOnce, lines.isEmpty(), room.canLoadEarlier, room.loadingEarlier, room.loading) {
        val wanted = lines.isEmpty() || (atTop && atEndOnce)
        if (wanted && room.canLoadEarlier && !room.loadingEarlier && !room.loading) onLoadEarlier()
    }
    when {
        (room.loading || room.loadingEarlier || room.canLoadEarlier) && lines.isEmpty() -> ListSpinner()
        lines.isEmpty() -> ListNotice("The room is quiet. Say something to start it.")
        else -> Box(Modifier.fillMaxSize()) {
            TranscriptList(lines, listState, faces, onOpenFile)
            // Over the list, not in it: a row on top would hold the scroll there and pull the reader up.
            if (room.loadingEarlier) {
                Spinner(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(18.dp))
            }
        }
    }
}

/** The transcript's lines, keyed by seq. */
@Composable
private fun TranscriptList(lines: List<RoomLine>, listState: LazyListState, faces: BotFaces, onOpenFile: (String?, GatewayFile) -> Unit) = ScrollbarBox(listState, Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(lines, key = { _, line -> line.seq }) { index, line ->
            when (line) {
                is RoomLine.Message -> if (line.fromUser) {
                    UserLine(line)
                } else {
                    // A bot that goes on talking keeps its face and name from its first line.
                    val previous = lines.getOrNull(index - 1) as? RoomLine.Message
                    val continued = previous != null && !previous.fromUser &&
                        previous.profile == line.profile && previous.speaker == line.speaker
                    MemberLine(line, continued, faces, onOpenFile)
                }
                is RoomLine.System -> SystemLine(line)
            }
        }
    }
}

/**
 * The user's message: a bubble on the right, its corner toward the user tucked in. Shared with the
 * Desktop room's read-only copy.
 */
@Composable
internal fun UserLine(line: RoomLine.Message) {
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (line.attachments.isNotEmpty()) SentAttachments(remember(line.attachments) {
            line.attachments.map { file -> ShownAttachment(file.attachmentId, file.name, when (file.kind) {
                "image" -> AttachmentKind.Image
                "pdf" -> AttachmentKind.Pdf
                else -> AttachmentKind.File
            }) }
        })
        // Copy sits beside the bubble, at its foot, so it takes no height of its own. A screen reader still
        // reads the message first, as on a bot's line. Files sent alone have no bubble.
        if (line.text.isNotBlank()) Row(
            Modifier.fillMaxWidth(0.84f).semantics { isTraversalGroup = true },
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (copyable(line.text)) {
                CopyButton(line.text, Modifier.semantics { traversalIndex = 1f }, label = copyLabel(null))
            }
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .clip(shape)
                    .background(Theme[colors][userBubble], shape)
                    .border(1.dp, Theme[colors][userBubbleStroke], shape)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                SelectionContainer {
                    Text(line.text, style = Theme[typography][body], color = Theme[colors][onUserBubble])
                }
            }
        }
        MessageTimeLabel(line.createdAt)
    }
}

/**
 * A bot's message: its face, its name in its own color, then what it said in a ringed bubble, the corner
 * toward its face tucked in.
 */
@Composable
internal fun MemberLine(line: RoomLine.Message, continued: Boolean, faces: BotFaces, onOpenFile: (String?, GatewayFile) -> Unit = { _, _ -> }) {
    val bot = remember(line.profile, faces) { faces.roomBot(line.profile) }
    val tint = bot.roomColor()
    val name = line.speaker ?: bot.label
    // Kept across recompositions: a new handler would redraw the whole message each time the room updates.
    val open by rememberUpdatedState(onOpenFile)
    val fileLinks = remember(line.profile) { { link: String -> roomFileLink(link, line.profile, open) } }
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(FACE)) {
            if (!continued) RoomMemberFace(bot, faces, FACE)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!continued) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = Theme[typography][caption].copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        // The bot's own color, drawn toward the text color so it reads on either theme: further on a
                        // light one, where a yellow or cyan name would wash out.
                        color = lerp(tint, Theme[colors][text], if (Theme[colors][background].luminance() > 0.5f) 0.6f else 0.25f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    MessageTimeLabel(line.createdAt)
                }
            }
            // Copy sits beside the bubble, at its foot, so a bot's run of lines stays packed.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .clip(shape)
                        .background(Theme[colors][surface], shape)
                        .border(1.dp, Theme[colors][stroke], shape)
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                ) {
                    SelectionContainer {
                        CompositionLocalProvider(LocalFileLinks provides fileLinks) {
                            MarkdownText(remember(line.text) { linkFileUris(line.text) })
                        }
                    }
                }
                // Copy keeps the message as the bot wrote it, not with its paths turned into links.
                if (copyable(line.text)) CopyButton(line.text, label = copyLabel(name))
            }
        }
    }
}

/** Whether a line has text worth a Copy button. */
internal fun copyable(text: String): Boolean = text.isNotBlank()

/** What a screen reader hears for a line's Copy: whose message it copies, the user's when [speaker] is null. */
internal fun copyLabel(speaker: String?): String =
    if (speaker == null) "Copy your message" else "Copy $speaker's message"

/** A line the gateway wrote: centred and quiet between the messages. */
@Composable
private fun SystemLine(line: RoomLine.System) {
    Text(
        line.text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        style = Theme[typography][caption],
        color = Theme[colors][textTertiary],
        textAlign = TextAlign.Center,
    )
}

/** Approvals and retries the room waits on the user for, right above the composer. */
@Composable
private fun PendingActions(room: OpenRoom, onApprove: (RoomPendingAction, String) -> Unit, onRetry: (RoomPendingAction) -> Unit) {
    if (room.pendingActions.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        room.pendingActions.forEach { action ->
            Surface(Modifier.fillMaxWidth(), elevated = true) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val who = room.room.member(action.memberId)?.label ?: "A member"
                    if (action.kind == "approval") {
                        Text("$who needs your approval", style = Theme[typography][bodySmall], color = Theme[colors][warning])
                        action.approvalSummary?.let {
                            Text(it, style = Theme[typography][caption], color = Theme[colors][textSecondary])
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button("Deny", onClick = { onApprove(action, "deny") }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
                            Button("Approve once", onClick = { onApprove(action, "once") }, variant = ButtonVariant.Primary, size = ButtonSize.Small)
                        }
                    } else {
                        Text("$who's turn didn't go through", style = Theme[typography][bodySmall], color = Theme[colors][warning])
                        Button("Retry", onClick = { onRetry(action) }, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
                    }
                }
            }
        }
    }
}

/**
 * The chat's composer as a room needs it: the same frosted box and field, who's listening where the
 * model sits in a chat, and Send, or Stop while the room works (with Send beside it for a message
 * typed meanwhile).
 */
@Composable
private fun RoomComposer(room: OpenRoom, viewModel: RoomActions, mentions: RoomMentionList, keysEnabled: Boolean) {
    val hasText = viewModel.composer.text.isNotBlank() || room.attachments.isNotEmpty()
    var attachOpen by remember(room.draftKey) { mutableStateOf(false) }
    // A file read belongs to this opening, even if another room opens while it finishes.
    val picker = key(viewModel, room.draftKey) {
        val draftKey = room.draftKey
        rememberAttachmentPicker(
            onPicked = { viewModel.addAttachments(it, draftKey) },
            onError = { viewModel.attachmentError(it, draftKey) },
        )
    }
    AttachSheet(attachOpen, onDismiss = { attachOpen = false }, picker = picker,
        subtitle = RoomAttachment.LIMITS)
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // The desktop's keys, as the chat's composer has them: an opened room takes the keyboard unless something else
    // holds it, Ctrl+L comes back to it, and typing or a paste with nothing focused lands in it.
    val focusHeld = LocalFocusHeld.current
    LaunchedEffect(room.draftKey) {
        if (!isDesktop || !keysEnabled) return@LaunchedEffect
        withFrameNanos {}
        if (!focusHeld.value) focus.requestFocus()
    }
    ShortcutHandler(enabled = isDesktop && keysEnabled && !room.attachmentRetry) { shortcut ->
        fun write(text: String) {
            focus.requestFocus()
            viewModel.composer.edit {
                append(text)
                placeCursorAtEnd()
            }
        }
        when (shortcut) {
            Shortcut.FocusComposer -> true.also { focus.requestFocus() }
            is Shortcut.Type -> true.also { write(shortcut.text) }
            Shortcut.Paste -> when {
                room.attachmentsSupported && picker.pasteAttachments() -> true
                else -> picker.clipboardText()?.let { write(it) } != null
            }
            else -> false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .clip(shape)
            .background(Theme[colors][surface].copy(alpha = 0.55f))
            .border(1.dp, if (focused) Theme[colors][textTertiary] else Theme[colors][strokeStrong], shape)
            .onFocusChanged { focused = it.hasFocus }
            .padding(start = 4.dp, end = 6.dp, top = 14.dp, bottom = 6.dp),
    ) {
        if (room.attachments.isNotEmpty()) ComposerTray(room.attachments, onRemove = viewModel::removeAttachment)
        if (room.attachmentRetry) Text(
            if (room.retryText.isBlank()) "Send again to confirm the previous message."
            else "Send again to confirm the previous message: “${room.retryText}”", style = Theme[typography][caption], color = Theme[colors][textTertiary])
        UnstyledTextField(
            state = viewModel.composer,
            enabled = !room.attachmentRetry,
            textStyle = Theme[typography][body],
            textColor = Theme[colors][text],
            cursorBrush = SolidColor(Theme[colors][accent]),
            // UnstyledTextField defaults to unspecified colors, which hides the selection and its handles.
            selectionColors = LocalTextSelectionColors.current,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 8),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 12.dp).focusRequester(focus).then(
                // The desktop's keys, as the chat's composer has them: Enter sends, Shift+Enter starts a line,
                // and with the `@` list up, Up and Down mark a member and Enter or Tab takes it.
                if (!isDesktop) {
                    Modifier
                } else {
                    Modifier.onPreviewKeyEvent { event ->
                        mentions.onKey(event) || (composerKey(event) == ComposerKey.Send).also { send ->
                            if (send && hasText && !room.sending) viewModel.send()
                        }
                    }
                },
            ),
        ) {
            TextInput(
                placeholder = {
                    Text("Message the room", style = Theme[typography][body], color = Theme[colors][textTertiary])
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(Lucide.Paperclip, "Add photos or files", onClick = {
                // A desktop has no camera, and its file dialog picks photos as well: it opens straight away.
                if (room.attachmentsSupported) { if (isDesktop) picker.pickFiles() else attachOpen = true }
                else viewModel.attachmentError("This gateway doesn't support room attachments. Update Hermes to use them.", room.draftKey)
            }, enabled = !room.sending && !room.attachmentRetry && room.attachments.size < RoomAttachment.MAX_COUNT)
            // Who answers first, by the gateway's own @ rule: everyone, unless the message names someone.
            val members = room.room.members
            val recipients by remember(members) { derivedStateOf { roomRecipients(viewModel.composer.text.toString(), members) } }
            // Starts an @ where the cursor is, which brings up the members to pick from.
            BarButton(Lucide.AtSign, "Mention a member", onClick = {
                viewModel.composer.edit {
                    val at = selection
                    val previous = asCharSequence().getOrNull(at.start - 1)
                    // A second tap doesn't start another @.
                    if (at.collapsed && previous == '@') return@edit
                    val before = if (previous != null && !previous.isWhitespace()) " @" else "@"
                    replace(at.start, at.end, before)
                    // Over a selection, the cursor goes after the @ rather than selecting it.
                    placeCursorBeforeCharAt(at.start + before.length)
                }
            }, enabled = !room.attachmentRetry)
            Text(
                if (recipients.size == members.size) members.joinToString(" · ") { it.label }
                else "To ${recipients.joinToString(", ") { it.label }}",
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
            )
            if (room.working) {
                if (hasText) SendButton(SendIcon.Send, onClick = viewModel::send, enabled = !room.sending)
                SendButton(SendIcon.Stop, onClick = viewModel::stop, enabled = true)
            } else {
                SendButton(SendIcon.Send, onClick = viewModel::send, enabled = hasText && !room.sending)
            }
        }
    }
}

/**
 * The `@` list over the room's composer: "Everyone" and the members that what's typed after `@` can mean.
 * A pick puts the member's room handle in, which is what the gateway matches (not the chat's `@hermes`).
 */
@Composable
private fun RoomMentions(room: OpenRoom, mentions: RoomMentionList) {
    val choices = mentions.choices
    AnimatedVisibility(visible = !room.attachmentRetry && choices.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
        // Kept through the fade-out, so the list doesn't empty before it leaves.
        var shown by remember { mutableStateOf(choices) }
        if (choices.isNotEmpty()) shown = choices
        MentionSuggestions(shown, hazeState = null, marked = if (isDesktop) mentions.marked.coerceAtLeast(0) else -1, onPick = mentions::pick)
    }
}

/** The members what's typed after `@` can mean, the one Up and Down have marked on the desktop, and the pick. */
@Stable
private class RoomMentionList(val choices: List<MentionChoice>, private val pickAt: (MentionChoice) -> Unit) {
    var marked by mutableIntStateOf(-1)

    fun pick(choice: MentionChoice) = pickAt(choice)

    /** The desktop's keys in the composer while the list shows: Up and Down mark, Enter or Tab takes the marked one. */
    fun onKey(event: KeyEvent): Boolean {
        if (choices.isEmpty()) return false
        suggestionStep(event)?.let { step -> return true.also { marked = moveHighlight(marked, step, choices.size) } }
        if (!isPickKey(event)) return false
        pick(choices[marked.coerceIn(choices.indices)])
        return true
    }
}

@Composable
private fun rememberRoomMentions(room: OpenRoom, viewModel: RoomActions, faces: BotFaces): RoomMentionList {
    val members = room.room.members
    val mention by remember(viewModel) {
        derivedStateOf { viewModel.composer.let { mentionQuery(it.text.toString(), it.selection.end) } }
    }
    val retry = room.attachmentRetry
    return remember(mention, members, faces, retry) {
        val typed = mention
        val everyone = MentionChoice("Everyone", "all", bot = null).takeIf { typed != null && mentionsEveryone(typed.query) }
        val choices = if (typed == null || retry) emptyList() else listOfNotNull(everyone) + members.mentionable(typed.query).mapNotNull { member ->
            member.handle?.let { MentionChoice(member.label, it, faces.roomBot(member.profile ?: member.memberId)) }
        }
        RoomMentionList(choices) { choice ->
            if (!retry && typed != null) viewModel.composer.edit { replace(typed.start, typed.end, "@${choice.handle} ") }
        }
    }
}

/** How big a member's face is beside its lines. */
private val FACE = 30.dp

/** Opens the gateway file [link] points at as one of [profile]'s; a web link or anything else is left alone. */
internal fun roomFileLink(link: String, profile: String?, open: (String?, GatewayFile) -> Unit) {
    GatewayFile.fromLink(link)?.let { open(profile, it) }
}

/**
 * A room file keeps the gateway, the room and the speaking bot that own it, including for links inside its
 * preview. In a Desktop room [profile] is the member's name, and [shownIn] decides whether it is a bot here.
 */
internal data class RoomFileLink(val gateway: GatewayUrl, val room: String, val profile: String?, val file: GatewayFile) {
    /**
     * The link as the open [openRoom] shows it, or null when another room (or none) is open: a file saved
     * across process death can come back before its room does. A Desktop member's owner is looked up in
     * [bots] each time, so a tap made while the roster was still loading finds it once the roster arrives.
     */
    fun shownIn(openRoom: String?, bots: List<Bot>): RoomFileLink? = when {
        room != openRoom -> null
        room.startsWith(DESKTOP) -> copy(profile = desktopFileOwner(profile, bots))
        else -> this
    }

    suspend fun preview(media: MediaApi, linked: GatewayFile = file): FileLoad =
        if (profile.isNullOrBlank()) FileLoad.Failed(NO_OWNER) else media.fileLoad(gateway, linked, profile)

    suspend fun download(media: MediaApi, linked: GatewayFile = file): FileDownload =
        if (profile.isNullOrBlank()) FileDownload.Failed(NO_OWNER) else media.fileDownload(gateway, linked, profile)

    companion object {
        /** No request goes out without an owner: another profile's file at the same path is not this one. */
        const val NO_OWNER = "Kyllene can't tell which of this gateway's bots owns this file."

        private const val HOSTED = "hosted:"
        private const val DESKTOP = "desktop:"

        /** The key [room] takes for the open room: a hosted room by its id, a Desktop room by its mirror key. */
        fun roomKey(hostedRoomId: String?, desktopRoomKey: String?): String? =
            hostedRoomId?.let { HOSTED + it } ?: desktopRoomKey?.let { DESKTOP + it }

        /** Kept across rotation and a folding screen, so an open file stays open. */
        val Saver = Saver<RoomFileLink?, List<Any>>(
            save = { link -> link?.let { arrayListOf(it.gateway.value, it.room, it.profile.orEmpty(), it.file.path, it.file.line ?: 0) } },
            restore = { saved ->
                GatewayUrl.parseOrNull(saved[0] as String)?.let { gateway ->
                    val file = GatewayFile(saved[3] as String, (saved[4] as Int).takeIf { it > 0 })
                    RoomFileLink(gateway, saved[1] as String, (saved[2] as String).ifEmpty { null }, file)
                }
            },
        )
    }
}

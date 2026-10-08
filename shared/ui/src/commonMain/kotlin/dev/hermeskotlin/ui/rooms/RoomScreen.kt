package dev.hermeskotlin.ui.rooms

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.theme.Theme
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
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
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
import dev.hermeskotlin.ui.chat.SendButton
import dev.hermeskotlin.ui.chat.SendIcon
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
) {
    val open by viewModel.opened.collectAsStateWithLifecycle()
    val room = open ?: return
    val faces = LocalBotFaces.current
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
                subtitle = "Waiting on you".takeIf { room.pendingActions.isNotEmpty() },
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
                        BarButton(Lucide.EllipsisVertical, "Room options", onClick = { menuOpen = true })
                    }
                    BarButton(Lucide.X, "Close the room", onClick = onBack)
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Transcript(room, faces, onLoadEarlier = viewModel::loadEarlier)
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
            RoomMentions(room, viewModel, faces)
            RoomComposer(room, viewModel)
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
private fun Transcript(room: OpenRoom, faces: BotFaces, onLoadEarlier: () -> Unit) {
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
            TranscriptList(lines, listState, faces)
            // Over the list, not in it: a row on top would hold the scroll there and pull the reader up.
            if (room.loadingEarlier) {
                Spinner(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(18.dp))
            }
        }
    }
}

/** The transcript's lines, keyed by seq. */
@Composable
private fun TranscriptList(lines: List<RoomLine>, listState: LazyListState, faces: BotFaces) {
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
                    MemberLine(line, continued, faces)
                }
                is RoomLine.System -> SystemLine(line)
            }
        }
    }
}

/** The user's message: the chat's own prompt bubble. Shared with the Desktop room's read-only copy. */
@Composable
internal fun UserLine(line: RoomLine.Message) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Theme[colors][userBubble], shape)
                .border(1.dp, Theme[colors][userBubbleStroke], shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(line.text, style = Theme[typography][body], color = Theme[colors][text])
        }
        MessageTimeLabel(line.createdAt, Modifier.align(Alignment.End))
    }
}

/** A bot's message: its face and name, then what it said in a box tinted with its own color. */
@Composable
internal fun MemberLine(line: RoomLine.Message, continued: Boolean, faces: BotFaces) {
    val bot = remember(line.profile, faces) { faces.roomBot(line.profile) }
    val tint = bot.roomColor()
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(FACE)) {
            if (!continued) RoomMemberFace(bot, faces, FACE)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!continued) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        line.speaker ?: bot.label,
                        style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.SemiBold),
                        color = Theme[colors][text],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    MessageTimeLabel(line.createdAt)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tint.copy(alpha = 0.12f), shape)
                    .border(1.dp, tint.copy(alpha = 0.45f), shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                MarkdownText(line.text)
            }
        }
    }
}

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
private fun RoomComposer(room: OpenRoom, viewModel: RoomsViewModel) {
    val hasText = viewModel.composer.text.isNotBlank()
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    var focused by remember { mutableStateOf(false) }
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
        UnstyledTextField(
            state = viewModel.composer,
            textStyle = Theme[typography][body],
            textColor = Theme[colors][text],
            cursorBrush = SolidColor(Theme[colors][accent]),
            // UnstyledTextField defaults to unspecified colors, which hides the selection and its handles.
            selectionColors = LocalTextSelectionColors.current,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 8),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 12.dp),
        ) {
            TextInput(
                placeholder = {
                    Text("Message the room", style = Theme[typography][body], color = Theme[colors][textTertiary])
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Who answers first, by the gateway's own @ rule: everyone, unless the message names someone.
            val members = room.room.members
            val recipients by remember(members) { derivedStateOf { roomRecipients(viewModel.composer.text.toString(), members) } }
            Text(
                if (recipients.size == members.size) members.joinToString(" · ") { it.label }
                else "To ${recipients.joinToString(", ") { it.label }}",
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
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
private fun RoomMentions(room: OpenRoom, viewModel: RoomsViewModel, faces: BotFaces) {
    val members = room.room.members
    val mention by remember(viewModel) {
        derivedStateOf { viewModel.composer.let { mentionQuery(it.text.toString(), it.selection.end) } }
    }
    val choices = remember(mention, members, faces) {
        val typed = mention ?: return@remember emptyList()
        val everyone = MentionChoice("Everyone", "all", bot = null).takeIf { mentionsEveryone(typed.query) }
        listOfNotNull(everyone) + members.mentionable(typed.query).mapNotNull { member ->
            member.handle?.let { MentionChoice(member.label, it, faces.roomBot(member.profile ?: member.memberId)) }
        }
    }
    AnimatedVisibility(visible = choices.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
        // Kept through the fade-out, so the list doesn't empty before it leaves.
        var shown by remember { mutableStateOf(choices) }
        if (choices.isNotEmpty()) shown = choices
        MentionSuggestions(shown, hazeState = null) { choice ->
            val typed = mention ?: return@MentionSuggestions
            viewModel.composer.edit { replace(typed.start, typed.end, "@${choice.handle} ") }
        }
    }
}

/** How big a member's face is beside its lines. */
private val FACE = 32.dp

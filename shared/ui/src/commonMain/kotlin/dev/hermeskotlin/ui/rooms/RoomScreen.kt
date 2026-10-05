package dev.hermeskotlin.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.core.rooms.RoomPendingAction
import dev.hermeskotlin.core.rooms.RoomLine
import dev.hermeskotlin.ui.chat.SendButton
import dev.hermeskotlin.ui.chat.SendIcon
import dev.hermeskotlin.ui.sessions.ListNotice
import dev.hermeskotlin.ui.sessions.ListSpinner
import dev.hermeskotlin.ui.sessions.MessageBanner
import org.koin.compose.viewmodel.koinViewModel

/**
 * One hosted room, open: its transcript from the gateway's log, a composer to take part, and whatever
 * the room waits on the user for. The room keeps running on the gateway with nobody watching.
 */
@Composable
fun RoomScreen(
    viewModel: RoomsViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val open by viewModel.opened.collectAsStateWithLifecycle()
    val room = open ?: return
    Box(Modifier.fillMaxSize().background(Theme[colors][background])) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            RoomHeader(room, onBack = onBack, onStop = viewModel::stop)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Transcript(room)
            }
            room.error?.let { ListNotice(it) }
            PendingActions(room, onApprove = viewModel::approve, onRetry = viewModel::retry)
            if (room.working && room.pendingActions.isEmpty()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spinner(Modifier.size(14.dp))
                    Text("Working…", style = Theme[typography][caption], color = Theme[colors][success])
                }
            }
            RoomComposer(room, viewModel)
        }
        room.notice?.let { message ->
            MessageBanner(message, onDismiss = viewModel::dismissRoomNotice, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
        }
    }
}

/** The room's name and who's in it, with Stop while the driver works. */
@Composable
private fun RoomHeader(room: OpenRoom, onBack: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Lucide.ArrowLeft, contentDescription = "Back", onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(
                room.room.name,
                style = Theme[typography][body].copy(fontWeight = FontWeight.SemiBold),
                color = Theme[colors][text],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                membersLine(room),
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (room.working) {
            Button("Stop", onClick = onStop, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
        }
    }
}

private fun membersLine(room: OpenRoom): String {
    val who = room.room.members.map { it.label }
    val members = when {
        who.isEmpty() -> "No members"
        who.size == 1 -> who.first()
        else -> "${who.size} members: ${who.joinToString(", ")}"
    }
    return if (room.pendingActions.isNotEmpty()) "$members · waiting on you" else members
}

/** The transcript: messages, system lines, and nothing of the room's machinery. */
@Composable
private fun Transcript(room: OpenRoom) {
    val listState = rememberLazyListState()
    val lines = room.lines
    // A new line brings the end of the conversation into view.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    when {
        room.loading && lines.isEmpty() -> ListSpinner()
        lines.isEmpty() -> ListNotice("The room is quiet. Say something to start it.")
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(lines, key = { it.seq }) { line -> TranscriptLine(line) }
        }
    }
}

@Composable
private fun TranscriptLine(line: RoomLine) {
    when (line) {
        is RoomLine.Message -> if (line.fromUser) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
                        .background(Theme[colors][accentSoft])
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(line.text, style = Theme[typography][body], color = Theme[colors][text])
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                line.speaker?.let {
                    Text(it, style = Theme[typography][caption], color = Theme[colors][textSecondary], fontWeight = FontWeight.Medium)
                }
                Text(line.text, style = Theme[typography][body], color = Theme[colors][text])
            }
        }
        is RoomLine.System -> Text(
            line.text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
            textAlign = TextAlign.Center,
        )
    }
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

/** The open room's composer: text and a send button — messages land in the room's log. */
@Composable
private fun RoomComposer(room: OpenRoom, viewModel: RoomsViewModel) {
    val canSend = viewModel.composer.text.isNotBlank() && !room.sending
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            state = viewModel.composer,
            modifier = Modifier.weight(1f),
            placeholder = "Message the room",
            singleLine = false,
            maxLines = 4,
        )
        // The chat's own send, so a room reads like the rest of the app.
        SendButton(SendIcon.Send, onClick = viewModel::send, enabled = canSend)
    }
}

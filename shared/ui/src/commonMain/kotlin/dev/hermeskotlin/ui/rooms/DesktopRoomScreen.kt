package dev.hermeskotlin.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.rooms.DesktopLine
import dev.hermeskotlin.core.rooms.DesktopRoom
import dev.hermeskotlin.core.rooms.RoomLine
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.bots.BotFaces
import dev.hermeskotlin.ui.bots.LocalBotFaces
import dev.hermeskotlin.ui.chat.BarButton
import dev.hermeskotlin.ui.chat.BarStatus
import dev.hermeskotlin.ui.chat.StatusTone
import dev.hermeskotlin.ui.chat.TopBar

/**
 * One Desktop room, open: read-only. Desktop runs the room — its rounds, its log, its watermarks — so
 * this screen shows the mirror's newest lines with no composer, and says where to continue. Nothing
 * here ever writes to the mirror.
 */
@Composable
fun DesktopRoomScreen(room: DesktopRoom, onOpenSidebar: () -> Unit, onBack: () -> Unit) {
    val faces = LocalBotFaces.current
    val members = remember(room.key, room.members) { room.roomMembers() }
    val lines = remember(room.lines) { room.roomLines() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
            TopBar(
                title = room.name,
                titleFace = members.takeIf { it.isNotEmpty() }?.let { { RoomFaces(it, faces, size = 20.dp, max = 3) } },
                status = BarStatus("On Desktop", StatusTone.Neutral),
                onOpenSidebar = onOpenSidebar,
                onNewChat = null,
                onOpenMenu = null,
                trailing = { BarButton(Lucide.X, "Close the room", onClick = onBack) },
            )
            // Why there is no composer: the room keeps running on Desktop; this is its newest lines.
            Text(
                "Continue on Desktop — the room runs there. New lines show here as it syncs.",
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp),
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (lines.isEmpty()) {
                    Text(
                        "Nothing from the room yet.",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        style = Theme[typography][caption],
                        color = Theme[colors][textTertiary],
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Transcript(room, lines, faces)
                }
            }
        }
    }
}

/** The mirror's newest lines, drawn as the hosted room draws them; there is nothing older to read. */
@Composable
private fun Transcript(room: DesktopRoom, lines: List<RoomLine.Message>, faces: BotFaces) {
    val listState = rememberLazyListState()
    val omittedRow = if (room.omitted > 0) 1 else 0
    // The newest line is what an open room is read for: opening lands there, and a line synced in since
    // brings the end into view, as the hosted room does.
    val newest = lines.lastOrNull()?.eventId
    var atEndOnce by remember(room.key) { mutableStateOf(false) }
    LaunchedEffect(room.key, newest) {
        if (newest == null) return@LaunchedEffect
        val end = lines.size - 1 + omittedRow
        if (atEndOnce) listState.animateScrollToItem(end) else listState.scrollToItem(end)
        atEndOnce = true
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (room.omitted > 0) {
            item(key = "omitted") {
                Text(
                    "${room.omitted} earlier messages",
                    modifier = Modifier.fillMaxWidth(),
                    style = Theme[typography][caption],
                    color = Theme[colors][textTertiary],
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Keyed by message id: the copy is a sliding window, so a line's place in it moves as new ones come.
        itemsIndexed(lines, key = { _, line -> line.eventId }) { index, line ->
            if (line.fromUser) {
                UserLine(line)
            } else {
                // A member that goes on talking keeps its face and name from its first line.
                val previous = lines.getOrNull(index - 1)
                val continued = previous != null && !previous.fromUser && previous.profile == line.profile && previous.speaker == line.speaker
                MemberLine(line, continued, faces)
            }
        }
    }
}

/**
 * The copy's lines as the transcript draws them, each with a key of its own: the message id, or its place
 * when a legacy entry has none (or repeats one).
 */
internal fun DesktopRoom.roomLines(): List<RoomLine.Message> {
    val seen = mutableSetOf<String>()
    return lines.mapIndexed { index, line -> line.toRoomLine(index, line.id?.takeIf { seen.add(it) } ?: "desktop:$index") }
}

/**
 * The line as the transcript draws it; the mirror's times are epoch millis, the lines read seconds. The
 * mirror names a member by its profile ("default"), so the name shown is the bot's own label.
 */
private fun DesktopLine.toRoomLine(index: Int, key: String): RoomLine.Message = RoomLine.Message(
    seq = index,
    fromUser = fromUser,
    speaker = null,
    text = text,
    eventId = key,
    profile = speaker.takeUnless { fromUser },
    createdAt = at?.div(1000.0),
)

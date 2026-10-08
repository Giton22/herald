package dev.hermeskotlin.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.botLook
import dev.hermeskotlin.core.rooms.DesktopRoom
import dev.hermeskotlin.core.rooms.RoomMember
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.ui.bots.BotAvatar
import dev.hermeskotlin.ui.bots.BotFaces

/**
 * The bot behind a room member's [profile]: the gateway's own when it still has it, so its picture and
 * chosen look show, else one made up from the profile so the stock face and color stay the same.
 */
internal fun BotFaces.roomBot(profile: String?): Bot {
    val name = profile?.takeIf { it.isNotBlank() } ?: "member"
    return find(name) ?: Bot(name = name)
}

/** A bot's own color, the one its stock face is drawn in: what tints its lines in a room. */
internal fun Bot.roomColor(): Color = Color(0xFF000000 or botLook(this).color.toLong())

/** One member's face. */
@Composable
internal fun RoomMemberFace(bot: Bot, faces: BotFaces, size: Dp, modifier: Modifier = Modifier) {
    BotAvatar(bot, faces.picture(bot), modifier = modifier, size = size)
}

/**
 * Everyone in a room as overlapping faces, the first on top, each ringed in the page color so the
 * stack reads as separate heads. Past [max], the rest are left out.
 */
@Composable
internal fun RoomFaces(members: List<RoomMember>, faces: BotFaces, size: Dp, max: Int = 4) {
    val shown = members.take(max)
    if (shown.isEmpty()) return
    val ring = 1.5.dp
    val overlap = size * 0.35f
    Row(horizontalArrangement = Arrangement.spacedBy(-overlap)) {
        shown.forEachIndexed { index, member ->
            Box(
                Modifier
                    .zIndex((shown.size - index).toFloat())
                    .clip(RoundedCornerShape((size + ring * 2) * 0.32f))
                    .background(Theme[colors][background])
                    .padding(ring),
            ) {
                RoomMemberFace(faces.roomBot(member.profile ?: member.memberId), faces, size)
            }
        }
    }
}

/** A Desktop room's members as roster rows, so their faces draw exactly as their bots' rows do. */
internal fun DesktopRoom.roomMembers(): List<RoomMember> =
    members.map { RoomMember(memberId = it.name, profile = it.name, handle = it.handle, displayName = it.name) }

/**
 * A Desktop room's row subtitle: its newest line as "who: what" on one line, the member by its bot's label
 * (the mirror names it by profile, "default"); "Needs you · " first when the room asks for the user, so the
 * dot says why. "Continue on Desktop" while the copy has no lines.
 */
internal fun DesktopRoom.rowSubtitle(faces: BotFaces): String {
    val line = lines.lastOrNull() ?: return "Continue on Desktop"
    val who = if (line.fromUser) line.speaker else faces.roomBot(line.speaker).label
    val said = "$who: ${line.text}".replace(WHITESPACE, " ").trim()
    return if (needsYou) "Needs you · $said" else said
}

private val WHITESPACE = Regex("\\s+")

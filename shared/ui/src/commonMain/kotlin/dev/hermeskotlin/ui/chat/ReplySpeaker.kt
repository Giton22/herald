package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.ui.bots.AVATAR_EDGE
import dev.hermeskotlin.ui.bots.BotFaces
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.koinInject

/**
 * The bot answering in a chat, as drawn above each reply: its name and its face. Equal when what's drawn is (the name,
 * the face's look, the picture), so a roster refresh that changes something else (a bot's latest chat) doesn't redraw
 * the chat. Not a data class: its generated copy and components would treat all of [face] as part of the value.
 */
internal class ReplySpeaker(val name: String, val face: Bot, val picture: ByteArray?) {
    // The face by what [dev.hermeskotlin.ui.bots.DecodedBotAvatar] draws it from (name and uiMeta), the picture by
    // identity: the roster keeps the same bytes until the picture changes.
    override fun equals(other: Any?): Boolean =
        other is ReplySpeaker && name == other.name && face.name == other.face.name && face.uiMeta == other.face.uiMeta &&
            picture === other.picture

    override fun hashCode(): Int = name.hashCode() * 31 + face.name.hashCode()
}

/** The chat's bot, provided by the chat screen; null (no roster, an older gateway) shows the app's mark and "Hermes". */
internal val LocalReplySpeaker = staticCompositionLocalOf<ReplySpeaker?> { null }

/** The speaker's picture, decoded once for every reply that shows it; null draws its shape. */
internal val LocalReplySpeakerPicture = staticCompositionLocalOf<ImageBitmap?> { null }

/** Provides [speaker] to the replies under [content], its picture decoded once for all of them. */
@Composable
internal fun ProvideReplySpeaker(speaker: ReplySpeaker?, content: @Composable () -> Unit) {
    val picture = speaker?.picture?.let { rememberImageBitmap(it, maxEdge = AVATAR_EDGE) }
    CompositionLocalProvider(LocalReplySpeaker provides speaker, LocalReplySpeakerPicture provides picture, content = content)
}

/**
 * The main window's bot roster and launch profile, for a chat in a window of its own (the desktop's), which has no
 * roster to name its bot from. The main window keeps them current.
 */
class ReplySpeakerSource {
    internal val faces = MutableStateFlow(BotFaces())
    internal val launchProfile = MutableStateFlow<String?>(null)
}

/** Provides [target]'s bot to the chat under [content], named from the main window's roster. */
@Composable
fun ProvideReplySpeakerFor(target: ChatTarget, content: @Composable () -> Unit) {
    val source = koinInject<ReplySpeakerSource>()
    val faces by source.faces.collectAsState()
    val launch by source.launchProfile.collectAsState()
    ProvideReplySpeaker(replySpeaker(faces, target.bot?.name, target.profile, launch), content)
}

/**
 * The bot of a chat: the open bot's own, else the bot of the chat's [profile], or of [launchProfile] when the chat
 * names none (the gateway answers those as the profile it was launched as, which needn't be `default`). Null when
 * that isn't known yet or the roster has no such bot, which keeps the app's mark and "Hermes".
 */
internal fun replySpeaker(faces: BotFaces, botName: String?, profile: String?, launchProfile: String?): ReplySpeaker? {
    val name = profile ?: launchProfile
    val bot = botName?.let(faces::find)
        ?: name?.let { wanted -> faces.bots.firstOrNull { it.name.equals(wanted, ignoreCase = true) } }
    return bot?.let { ReplySpeaker(it.label, it, faces.picture(it)) }
}

package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.hermeskotlin.core.media.MediaType
import dev.hermeskotlin.core.media.PlaySource

/**
 * Audio or video a message shows: [source] is a gateway path, a web address or a `data:` URL, unless the
 * file is at hand in [bytes] (one the user sent from here). The player knows a clip by its [key], the same
 * wherever in the chat it shows; it lets go of every clip when the chat changes.
 */
internal class PlayableMedia(
    val key: String,
    val source: String?,
    val name: String,
    val type: MediaType,
    val bytes: ByteArray? = null,
)

/** Where the chat's player is, for one clip. */
internal enum class PlaybackPhase { Idle, Loading, Playing, Paused, Ended, Failed }

/** What the player says about the clip it holds; [key] is null when it holds none. */
internal data class PlaybackState(
    val key: String? = null,
    /** The clip held, for what shows it away from its message (the bar over the composer). */
    val media: PlayableMedia? = null,
    val phase: PlaybackPhase = PlaybackPhase.Idle,
    val positionMs: Long = 0,
    /** Null until the clip says how long it is (a live or unindexed stream may never). */
    val durationMs: Long? = null,
    val speed: Float = 1f,
    val error: String? = null,
    /** Width over height of the picture, once a video says; the window over the chat letterboxes to it. */
    val videoAspect: Float? = null,
) {
    /** The state as clip [key] shows it: idle unless the player holds that one. */
    fun of(key: String): PlaybackState = if (this.key == key) this else PlaybackState()
}

/**
 * The one player the app plays chat audio and video with: starting a clip stops the one before, wherever it
 * showed, and the video on screen (in the window over the chat or full screen) is its own ([video]), so a turn
 * of the phone keeps both. Where there's none ([NoMediaPlayer]), messages keep the file card.
 */
internal interface ChatMediaPlayer {
    val available: Boolean

    val state: StateFlow<PlaybackState>

    /** The video on screen, and where, if any. Another clip starting takes its place, so it goes. */
    val video: StateFlow<ShownVideo?>

    /**
     * Plays [media]: from the start, or on from where it is when it's the clip held. [resolve] works out where its
     * bytes come from, when they aren't at hand.
     */
    fun play(media: PlayableMedia, resolve: (suspend () -> PlaySource)?)

    fun pause()

    /** Plays on the clip held, from where it is (or again from the start once it ended). */
    fun resume()

    fun seekTo(key: String, positionMs: Long)

    fun setSpeed(key: String, speed: Float)

    /** Shows [media] in [mode] and plays it there. */
    fun openVideo(media: PlayableMedia, resolve: (suspend () -> PlaySource)?, mode: VideoMode = VideoMode.Popup)

    /** Moves the video on screen to [mode], playing on where it is. */
    fun showVideo(mode: VideoMode)

    /** Closes the video, wherever it shows, and lets go of it. */
    fun closeVideo()

    /** Lets go of the clip and the player's resources; the next [play] builds them again. */
    fun stop()

    /**
     * Says which chat is open, by a token its view model keeps for as long as that chat is open: a new one lets go
     * of the last chat's clip, while the same one again (the screen rebuilt for a turn of the phone) keeps it.
     */
    fun bindChat(token: Any)
}

internal object NoMediaPlayer : ChatMediaPlayer {
    override val available = false
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
    override val video: StateFlow<ShownVideo?> = MutableStateFlow(null)
    override fun play(media: PlayableMedia, resolve: (suspend () -> PlaySource)?) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun seekTo(key: String, positionMs: Long) = Unit
    override fun setSpeed(key: String, speed: Float) = Unit
    override fun openVideo(media: PlayableMedia, resolve: (suspend () -> PlaySource)?, mode: VideoMode) = Unit
    override fun showVideo(mode: VideoMode) = Unit
    override fun closeVideo() = Unit
    override fun stop() = Unit
    override fun bindChat(token: Any) = Unit
}

/** The player the messages under it play with. */
internal val LocalChatMediaPlayer = staticCompositionLocalOf<ChatMediaPlayer> { NoMediaPlayer }

/** Works out how to play a message's clip for the open chat (see `ChatViewModel.playSource`). */
internal val LocalMediaSources = staticCompositionLocalOf<(suspend (String) -> PlaySource)?> { null }

/**
 * Asked as a clip starts: the chat's own voice (a reply read aloud) stops for it, and false keeps it from
 * starting at all (during a voice chat, whose microphone would hear it).
 */
internal val LocalMediaStarting = staticCompositionLocalOf<() -> Boolean> { { true } }

/** The app's player, one for the whole process. */
@Composable
internal expect fun rememberChatMediaPlayer(): ChatMediaPlayer

/**
 * The picture of the video [player] holds: with the platform's own controls full screen, bare in the window over
 * the chat ([controls] false), whose own buttons sit on top. Only the newest surface shown has the picture.
 */
@Composable
internal expect fun VideoSurface(player: ChatMediaPlayer, modifier: Modifier, controls: Boolean = true)

/** Speeds the audio row steps through. */
internal val PLAYBACK_SPEEDS = listOf(1f, 1.5f, 2f)

/** The speed after [speed] in [PLAYBACK_SPEEDS], back to the first after the last. */
internal fun nextSpeed(speed: Float): Float = PLAYBACK_SPEEDS.getOrElse(PLAYBACK_SPEEDS.indexOf(speed) + 1) { PLAYBACK_SPEEDS[0] }

/** `1x`, `1.5x`: a speed as the button says it. */
internal fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

/** `0:07`, `12:34`, `1:02:03`: a time in a clip; negative times read as zero. */
internal fun formatPlayTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

/** What the row says of where a clip is: `0:12 / 3:45`, or just the time when its length isn't known. */
internal fun playTimeLabel(positionMs: Long, durationMs: Long?): String =
    if (durationMs == null || durationMs <= 0) formatPlayTime(positionMs) else "${formatPlayTime(positionMs)} / ${formatPlayTime(durationMs)}"

/** Where a drag to [fraction] of the bar lands, inside the clip; null when its length isn't known yet. */
internal fun seekPosition(fraction: Float, durationMs: Long?): Long? =
    durationMs?.takeIf { it > 0 }?.let { (it * fraction.coerceIn(0f, 1f)).toLong() }

/**
 * Keeps one clip at a time: which one the player holds, and whether a new one replaces it. Shared by the
 * platform players so the rules are tested once.
 */
internal class PlayerSlot {
    var key: String? = null
        private set

    /** What a [claim] asks of the player. */
    enum class Claim {
        /** The clip is held already: carry on (or start again from the end). */
        Resume,

        /** Another clip, or none, is held: load this one from the start. */
        Load,
    }

    fun claim(key: String): Claim {
        if (this.key == key) return Claim.Resume
        this.key = key
        return Claim.Load
    }

    /** Whether a call about [key] (a seek, a speed) is about the clip held. */
    fun holds(key: String): Boolean = this.key == key

    fun clear() {
        key = null
    }
}

/** Why a clip didn't play, as the file viewer names it. */
internal fun playbackProblem(result: dev.hermeskotlin.core.network.ApiResult<*>): FileProblem = fileError(result, whole = true)

/** The same, in the words the row shows. */
internal suspend fun playbackError(result: dev.hermeskotlin.core.network.ApiResult<*>): String = fileProblemWords(playbackProblem(result))

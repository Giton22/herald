package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.hermeskotlin.core.media.MediaType

/** Where a playing video shows: the small window over the chat, or full screen. */
internal enum class VideoMode { Popup, Full }

/** The video the player shows, and where. */
internal data class ShownVideo(val media: PlayableMedia, val mode: VideoMode)

/**
 * The video still shown once clip [key] starts: the player plays one clip, so any other one, audio included,
 * takes the video's place and its window goes.
 */
internal fun ShownVideo?.afterPlay(key: String): ShownVideo? = this?.takeIf { it.media.key == key }

/** The shown video moved to [mode]; nothing when none is shown, as there's nothing to move. */
internal fun ShownVideo?.movedTo(mode: VideoMode): ShownVideo? = this?.copy(mode = mode)

/**
 * The bar over the composer shows the audio clip the player holds while its row in the chat is out of sight, so
 * there are never two controls for it on screen. A clip that failed says so on its row and drops off the bar.
 */
internal fun miniPlayerShows(state: PlaybackState, rowInView: Boolean): Boolean =
    state.media?.type == MediaType.Audio && state.key != null && !rowInView &&
        state.phase in setOf(PlaybackPhase.Loading, PlaybackPhase.Playing, PlaybackPhase.Paused, PlaybackPhase.Ended)

/** A row counts as seen while at least half of it is inside [viewport]. */
internal fun rowInView(row: Rect, viewport: Rect): Boolean {
    if (row.height <= 0f) return false
    val seen = minOf(row.bottom, viewport.bottom) - maxOf(row.top, viewport.top)
    return seen >= row.height / 2
}

/** The corners the video window settles in. */
internal enum class PopupCorner { TopStart, TopEnd, BottomStart, BottomEnd }

/**
 * Where the window's top left sits in [corner] of [area], [margin] in from its edges. When the area is too short
 * for the window (the keyboard up on a small phone), it keeps to the top, under the bar, rather than over it.
 */
internal fun cornerOffset(corner: PopupCorner, area: Rect, window: Size, margin: Float): Offset {
    val left = area.left + margin
    val right = (area.right - margin - window.width).coerceAtLeast(left)
    val top = area.top + margin
    val bottom = (area.bottom - margin - window.height).coerceAtLeast(top)
    return when (corner) {
        PopupCorner.TopStart -> Offset(left, top)
        PopupCorner.TopEnd -> Offset(right, top)
        PopupCorner.BottomStart -> Offset(left, bottom)
        PopupCorner.BottomEnd -> Offset(right, bottom)
    }
}

/** The corner of [area] the window dropped with its top left at [at] is nearest to, by the window's middle. */
internal fun nearestCorner(at: Offset, area: Rect, window: Size): PopupCorner {
    val middle = Offset(at.x + window.width / 2, at.y + window.height / 2)
    val start = middle.x < area.center.x
    val top = middle.y < area.center.y
    return when {
        top && start -> PopupCorner.TopStart
        top -> PopupCorner.TopEnd
        start -> PopupCorner.BottomStart
        else -> PopupCorner.BottomEnd
    }
}

/**
 * Which of the chat's audio rows are in sight, for the bar over the composer, and which message each is in, so a
 * tap on the bar can bring it back. Rows report themselves as they are laid out; the list says where it shows.
 */
@Stable
internal class ClipRows {
    private data class Seen(val clip: String, val bounds: Rect)

    /** The part of the window the messages can be read in: the list, less what floats over its bottom. */
    var viewport: Rect? by mutableStateOf(null)

    /** A clip whose message to bring into view; the list takes it and clears it. */
    var goTo: String? by mutableStateOf(null)

    private val rows = mutableStateMapOf<Any, Seen>()
    private val owners = mutableMapOf<String, String>()

    /** Row [row] shows [clip] in message [message] at [bounds], in window coordinates. */
    fun report(row: Any, clip: String, message: String?, bounds: Rect) {
        // Laid out again every frame of a scroll: only a move tells the bar anything.
        val seen = Seen(clip, bounds)
        if (rows[row] != seen) rows[row] = seen
        if (message != null) owners[clip] = message
    }

    /** Row [row] left the screen (its message scrolled out of the list). */
    fun forget(row: Any) {
        rows.remove(row)
    }

    fun inView(clip: String): Boolean {
        val viewport = viewport ?: return false
        return rows.values.any { it.clip == clip && rowInView(it.bounds, viewport) }
    }

    /** Where [clip]'s row was last laid out, while it's on the list at all. */
    fun boundsOf(clip: String): Rect? = rows.values.firstOrNull { it.clip == clip }?.bounds

    /** The message [clip] was last seen in. */
    fun messageOf(clip: String): String? = owners[clip]

    /** Asks the list to bring [clip]'s message into view; false when it isn't known. */
    fun show(clip: String): Boolean {
        if (clip !in owners) return false
        goTo = clip
        return true
    }
}

/** The open chat's audio rows; none outside the chat (rooms, the assistant). */
internal val LocalClipRows = staticCompositionLocalOf<ClipRows?> { null }

/** The key of the message being drawn, for a row inside it to say where it is. */
internal val LocalMessageKey = staticCompositionLocalOf<String?> { null }

/**
 * The views a video can be drawn into, each for one [VideoMode], newest last. The player draws into one alone: the
 * newest for the mode the video shows in, so the window kept under full screen doesn't take the picture, whichever
 * a rebuilt screen lays out first; else the newest of any.
 */
internal class SurfaceOwners<T : Any> {
    private val shown = mutableListOf<Pair<T, VideoMode>>()

    /** The view to draw into while the video shows in [mode]. */
    fun owner(mode: VideoMode?): T? = (shown.lastOrNull { it.second == mode } ?: shown.lastOrNull())?.first

    /** [view] is on screen, for [mode]. */
    fun add(view: T, mode: VideoMode) {
        if (shown.none { it.first === view }) shown += view to mode
    }

    /** [view] left the screen. */
    fun remove(view: T) {
        shown.removeAll { it.first === view }
    }

    fun all(): List<T> = shown.map { it.first }
}

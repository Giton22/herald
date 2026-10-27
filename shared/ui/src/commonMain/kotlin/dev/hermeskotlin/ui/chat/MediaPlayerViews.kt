package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.CirclePlay
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.PictureInPicture2
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.core.media.MediaType
import dev.hermeskotlin.core.media.PlaySource
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.KeepScreenOn
import dev.hermeskotlin.ui.fullScreenDialogProperties
import kotlinx.coroutines.launch

/**
 * A clip in a message: audio as a compact player row, video as a card that plays it in a window over the chat
 * (from where it goes full screen). Save and Share
 * stay on it, fetched the way the file card fetches them; [save] is null where there's nothing to fetch.
 */
@Composable
internal fun MediaCard(
    media: PlayableMedia,
    save: (suspend () -> ByteArray?)?,
    failure: String = "Couldn't get ${media.name}.",
) {
    val player = LocalChatMediaPlayer.current
    val sources = LocalMediaSources.current
    val starting = LocalMediaStarting.current
    val state = player.state.collectAsStateWithLifecycle().value.of(media.key)
    val shown = player.video.collectAsStateWithLifecycle().value?.takeIf { it.media.key == media.key }
    val resolve: (suspend () -> PlaySource)? = media.source?.let { source -> sources?.let { find -> suspend { find(source) } } }
    val canPlay = media.bytes != null || resolve != null
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val actions = rememberMediaActions()
    val notify = LocalNotice.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun withBytes(action: suspend (ByteArray) -> Unit) {
        if (busy || save == null) return
        busy = true
        scope.launch {
            try {
                val bytes = save()
                if (bytes == null) notify(failure) else action(bytes)
            } finally {
                busy = false
            }
        }
    }

    fun toggle() {
        when {
            !canPlay -> Unit
            state.phase == PlaybackPhase.Playing || state.phase == PlaybackPhase.Loading -> player.pause()
            !starting() -> notify("It can play once the voice chat or dictation ends.")
            // Where it shows already (full screen is over the card, so that's the window), else in a window.
            media.type == MediaType.Video -> player.openVideo(media, resolve, shown?.mode ?: VideoMode.Popup)
            else -> player.play(media, resolve)
        }
    }

    // An audio row says where it is, so the bar over the composer stands in for it only once it's scrolled away.
    val rows = LocalClipRows.current.takeIf { media.type == MediaType.Audio }
    val message = LocalMessageKey.current
    val row = remember { Any() }
    if (rows != null) DisposableEffect(rows, row) { onDispose { rows.forget(row) } }
    Column(
        Modifier
            .then(
                if (rows == null) Modifier else Modifier.onGloballyPositioned { coordinates ->
                    // Unclipped, so a row half under the composer reads as half seen.
                    rows.report(row, media.key, message, Rect(coordinates.positionInWindow(), coordinates.size.toSize()))
                },
            )
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .background(Theme[colors][surface])
            .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayButton(media, state, enabled = canPlay, onClick = ::toggle)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(media.name, style = Theme[typography][label], color = Theme[colors][textColor], maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (media.type == MediaType.Audio && state.key != null && state.phase != PlaybackPhase.Failed) {
                    SeekBar(state, onSeek = { player.seekTo(media.key, it) })
                }
                Text(
                    statusLine(media, state, canPlay, shown?.mode),
                    style = Theme[typography][caption],
                    color = if (state.phase == PlaybackPhase.Failed) Theme[colors][danger] else Theme[colors][textTertiary],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (media.type == MediaType.Audio && state.key != null && state.phase != PlaybackPhase.Failed) {
                SpeedButton(state.speed) { player.setSpeed(media.key, nextSpeed(state.speed)) }
            }
            if (save != null) {
                if (busy) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Spinner(Modifier.size(18.dp)) }
                } else {
                    IconButton(Lucide.Download, contentDescription = "Save ${media.name}", onClick = { withBytes { notify(actions.save(it, media.name)) } })
                    IconButton(Lucide.Share2, contentDescription = "Share ${media.name}", onClick = { withBytes { actions.share(it, media.name) } })
                }
            }
        }
    }
}

/** What the row says under the name: the time, or why it can't play. */
private fun statusLine(media: PlayableMedia, state: PlaybackState, canPlay: Boolean, mode: VideoMode?): String {
    val kind = if (media.type == MediaType.Audio) "Audio" else "Video"
    val ext = media.name.substringAfterLast('.', "").uppercase()
    val idle = listOf(kind, ext).filter { it.isNotEmpty() }.distinct().joinToString(" · ")
    return when {
        !canPlay -> "$idle · can't be played here"
        state.phase == PlaybackPhase.Failed -> state.error ?: "Couldn't play it."
        state.key == null -> idle
        state.phase == PlaybackPhase.Loading && state.durationMs == null -> "Loading…"
        media.type == MediaType.Video -> {
            val where = if (mode == VideoMode.Full) "Full screen" else "In a window"
            "$where · ${playTimeLabel(state.positionMs, state.durationMs)}"
        }
        else -> playTimeLabel(state.positionMs, state.durationMs)
    }
}

@Composable
private fun PlayButton(media: PlayableMedia, state: PlaybackState, enabled: Boolean, onClick: () -> Unit) {
    val playing = state.phase == PlaybackPhase.Playing
    val loading = state.phase == PlaybackPhase.Loading
    val icon = when {
        playing -> Lucide.Pause
        state.phase == PlaybackPhase.Failed -> Lucide.RotateCcw
        media.type == MediaType.Video -> Lucide.CirclePlay
        else -> Lucide.Play
    }
    val description = when {
        playing || loading -> "Pause ${media.name}"
        state.phase == PlaybackPhase.Failed -> "Try playing ${media.name} again"
        else -> "Play ${media.name}"
    }
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).padding(4.dp).clip(CircleShape).background(if (enabled) Theme[colors][accent] else Theme[colors][surface3]),
        indication = rememberColoredIndication(Theme[colors][onAccent]),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                Spinner(Modifier.size(18.dp).semantics { contentDescription = description }, color = Theme[colors][onAccent])
            } else {
                UnstyledIcon(icon, contentDescription = description, tint = if (enabled) Theme[colors][onAccent] else Theme[colors][textTertiary], modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** How far through the clip, dragged or tapped to move; read by a screen reader as a slider. */
@Composable
private fun SeekBar(state: PlaybackState, onSeek: (Long) -> Unit) {
    val duration = state.durationMs
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: duration?.takeIf { it > 0 }?.let { (state.positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val track = Theme[colors][surface3]
    val fill = Theme[colors][accent]
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .semantics {
                stateDescription = playTimeLabel(state.positionMs, duration)
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (duration != null) {
                    setProgress { target ->
                        seekPosition(target, duration)?.let(onSeek)
                        true
                    }
                }
            }
            .pointerInput(duration) {
                detectTapGestures { offset -> seekPosition(offset.x / size.width, duration)?.let(onSeek) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragging?.let { seekPosition(it, duration)?.let(onSeek) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                    onHorizontalDrag = { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) },
                )
            },
    ) {
        val height = 4.dp.toPx()
        val top = (size.height - height) / 2
        drawRoundRect(track, Offset(0f, top), Size(size.width, height), CornerRadius(height / 2))
        drawRoundRect(fill, Offset(0f, top), Size(size.width * fraction, height), CornerRadius(height / 2))
        drawCircle(fill, radius = 6.dp.toPx(), center = Offset(size.width * fraction, size.height / 2))
    }
}

@Composable
private fun SpeedButton(speed: Float, onClick: () -> Unit) {
    UnstyledButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).padding(6.dp).clip(RoundedCornerShape(Theme[radii][radiusMedium])).background(Theme[colors][accentSoft]),
        indication = rememberColoredIndication(Theme[colors][accentText]),
    ) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = "Playback speed ${speedLabel(speed)}" }, contentAlignment = Alignment.Center) {
            Text(speedLabel(speed), style = Theme[typography][caption], color = Theme[colors][accentText])
        }
    }
}

/**
 * The video the player shows full screen, over whatever is open. Its state lives in the player, so a turn of the
 * phone or a fold rebuilds the screen around the same playback. Back or the window button hands it back to the
 * window over the chat, playing on; Close ends it.
 */
@Composable
internal fun VideoViewer() {
    val player = LocalChatMediaPlayer.current
    val media = player.video.collectAsStateWithLifecycle().value?.takeIf { it.mode == VideoMode.Full }?.media ?: return
    val state = player.state.collectAsStateWithLifecycle().value.of(media.key)
    KeepScreenOn(state.phase == PlaybackPhase.Playing)
    Dialog(onDismissRequest = { player.showVideo(VideoMode.Popup) }, properties = fullScreenDialogProperties()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(player, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
            if (state.phase == PlaybackPhase.Failed) {
                Text(
                    state.error ?: "Couldn't play this video.",
                    style = Theme[typography][label],
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else if (state.phase == PlaybackPhase.Loading && state.durationMs == null) {
                Spinner(Modifier.align(Alignment.Center).size(32.dp), color = Color.White)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(Lucide.X, contentDescription = "Close", onClick = player::closeVideo, tint = Color.White)
                Text(
                    media.name,
                    style = Theme[typography][label],
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconButton(Lucide.PictureInPicture2, contentDescription = "Play in a window", onClick = { player.showVideo(VideoMode.Popup) }, tint = Color.White)
            }
        }
    }
}

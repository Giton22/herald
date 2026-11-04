package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.KeepScreenOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The audio clip the player holds, over the composer while its row is scrolled out of sight: its name (a tap goes
 * back to it), play and pause, how far it is, and Close, which stops it. At its end it stays, paused, offering to
 * play again as the row does, until closed or another clip starts.
 */
@Composable
internal fun MiniPlayer(player: ChatMediaPlayer, rows: ClipRows, starting: () -> Boolean, onNotice: (String) -> Unit) {
    val state by player.state.collectAsStateWithLifecycle()
    val shows by remember(rows) { derivedStateOf { miniPlayerShows(state, state.key?.let(rows::inView) == true) } }
    val waitForVoice = stringResource(Res.string.chat_media_wait_for_voice)
    // Kept through the fold away, so the bar doesn't empty before it leaves.
    var last by remember { mutableStateOf(state) }
    if (shows) last = state
    AnimatedVisibility(visible = shows, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        MiniPlayerBar(
            state = last,
            onToggle = {
                when {
                    last.phase == PlaybackPhase.Playing || last.phase == PlaybackPhase.Loading -> player.pause()
                    !starting() -> onNotice(waitForVoice)
                    else -> player.resume()
                }
            },
            onShow = { last.key?.let(rows::show) },
            onClose = player::stop,
        )
    }
}

@Composable
private fun MiniPlayerBar(state: PlaybackState, onToggle: () -> Unit, onShow: () -> Unit, onClose: () -> Unit) {
    val name = state.media?.name.orEmpty()
    val duration = state.durationMs?.takeIf { it > 0 }
    val fraction = duration?.let { (state.positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val pause = stringResource(Res.string.chat_media_pause_named, name)
    Surface(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), elevated = true) {
        Column {
            Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                when (state.phase) {
                    PlaybackPhase.Loading -> Box(
                        Modifier.size(40.dp).clickable(onClick = onToggle).semantics { contentDescription = pause },
                        contentAlignment = Alignment.Center,
                    ) { Spinner(Modifier.size(18.dp)) }
                    PlaybackPhase.Playing -> IconButton(Lucide.Pause, contentDescription = pause, onClick = onToggle)
                    PlaybackPhase.Ended -> IconButton(Lucide.RotateCcw, contentDescription = stringResource(Res.string.chat_media_replay_named, name), onClick = onToggle)
                    else -> IconButton(Lucide.Play, contentDescription = stringResource(Res.string.chat_media_play_named, name), onClick = onToggle)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClickLabel = stringResource(Res.string.chat_media_show_in_chat), onClick = onShow)
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    Text(name, style = Theme[typography][label], color = Theme[colors][textColor], maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(playTimeLabel(state.positionMs, state.durationMs), style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
                }
                IconButton(Lucide.X, contentDescription = stringResource(Res.string.chat_media_stop_named, name), onClick = onClose)
            }
            // How far through, as a hairline along the bottom; the row in the chat has the seek bar.
            Box(Modifier.fillMaxWidth().height(2.dp).background(Theme[colors][surface3])) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Theme[colors][accent]))
            }
        }
    }
}

/** How wide the video window stands, as a part of the chat's width, and its bounds. */
private const val POPUP_WIDTH = 0.45f
private val POPUP_MIN_WIDTH = 160.dp
private val POPUP_MAX_WIDTH = 360.dp
private val POPUP_MARGIN = 12.dp

/** How long the window's buttons stay after a tap while the video plays. */
private const val POPUP_CONTROLS_MS = 3_000L

/**
 * The video the player holds, in a small window over the chat that the user can read and type under. It can be
 * dragged anywhere and settles in the nearest corner of the conversation, clear of the top bar, the keyboard and
 * the composer ([bottomInset]); a tap shows play and pause, full screen, and Close. Full screen opens over it and hands the
 * picture back when it closes, the clip playing on throughout.
 */
@Composable
internal fun VideoPopup(player: ChatMediaPlayer, bottomInset: Dp, starting: () -> Boolean, onNotice: (String) -> Unit) {
    val shown = player.video.collectAsStateWithLifecycle().value ?: return
    val media = shown.media
    val state = player.state.collectAsStateWithLifecycle().value.of(media.key)
    // Under full screen the window stays, so its view still holds the last frame when the picture comes back.
    if (shown.mode == VideoMode.Popup) KeepScreenOn(state.phase == PlaybackPhase.Playing)
    var corner by rememberSaveable { mutableStateOf(PopupCorner.BottomEnd) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthDp = (maxWidth * POPUP_WIDTH).coerceIn(POPUP_MIN_WIDTH, POPUP_MAX_WIDTH.coerceAtMost(maxWidth))
        val window = with(density) { Size(widthDp.toPx(), widthDp.toPx() * 9f / 16f) }
        val area = with(density) { Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight - bottomInset.toPx()) }
        val margin = with(density) { POPUP_MARGIN.toPx() }
        val target = cornerOffset(corner, area, window, margin)
        val position = remember { Animatable(target, Offset.VectorConverter) }
        var dragging by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        // The corner's place moves with the keyboard and the composer; the window follows unless held.
        LaunchedEffect(target) { if (!dragging) position.animateTo(target) }
        var controls by remember { mutableStateOf(false) }
        val inWindow = stringResource(Res.string.chat_media_video_in_window, media.name)
        val toggleControls = stringResource(if (controls) Res.string.chat_media_hide_controls else Res.string.chat_media_show_controls)
        val waitForVoice = stringResource(Res.string.chat_media_wait_for_voice)
        LaunchedEffect(controls, state.phase) {
            if (controls && state.phase == PlaybackPhase.Playing) {
                delay(POPUP_CONTROLS_MS)
                controls = false
            }
        }
        // Read by the gestures as they are now: the keyboard opening mid-drag mustn't restart (and strand) the drag.
        val bounds by rememberUpdatedState(area)
        val size by rememberUpdatedState(window)
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
                .size(widthDp, widthDp * 9f / 16f)
                .shadow(12.dp, shape)
                .clip(shape)
                .background(Color.Black),
        ) {
            val aspect = state.videoAspect
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VideoSurface(player, if (aspect != null) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize(), controls = false)
            }
            // Over the picture, so the video's own view never takes the touches.
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { controls = !controls } }
                    .pointerInput(Unit) {
                        fun settle() {
                            dragging = false
                            scope.launch { position.animateTo(cornerOffset(corner, bounds, size, margin)) }
                        }
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                corner = nearestCorner(position.value, bounds, size)
                                settle()
                            },
                            onDragCancel = ::settle,
                            onDrag = { change, amount ->
                                change.consume()
                                val next = position.value + amount
                                val x = next.x.coerceIn(bounds.left, (bounds.right - size.width).coerceAtLeast(bounds.left))
                                val y = next.y.coerceIn(bounds.top, (bounds.bottom - size.height).coerceAtLeast(bounds.top))
                                scope.launch { position.snapTo(Offset(x, y)) }
                            },
                        )
                    }
                    .semantics {
                        contentDescription = inWindow
                        onClick(label = toggleControls) {
                            controls = !controls
                            true
                        }
                    },
            )
            when (state.phase) {
                PlaybackPhase.Failed -> Text(
                    state.failure?.text() ?: stringResource(Res.string.chat_media_video_failed),
                    style = Theme[typography][caption],
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                )
                PlaybackPhase.Loading -> Spinner(Modifier.align(Alignment.Center).size(24.dp), color = Color.White)
                else -> Unit
            }
            // After a tap (a screen reader's double tap too), or while it isn't playing (paused when Kyllene was left).
            val visible = controls || state.phase !in setOf(PlaybackPhase.Playing, PlaybackPhase.Loading)
            PopupControls(
                visible = visible,
                media = media,
                state = state,
                onToggle = {
                    when {
                        state.phase == PlaybackPhase.Playing || state.phase == PlaybackPhase.Loading -> player.pause()
                        !starting() -> onNotice(waitForVoice)
                        else -> player.resume()
                    }
                },
                onExpand = { player.showVideo(VideoMode.Full) },
                onClose = player::closeVideo,
            )
        }
    }
}

@Composable
private fun PopupControls(
    visible: Boolean,
    media: PlayableMedia,
    state: PlaybackState,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
            IconButton(Lucide.Maximize2, contentDescription = stringResource(Res.string.chat_media_full_screen), onClick = onExpand, tint = Color.White, modifier = Modifier.align(Alignment.TopStart))
            IconButton(Lucide.X, contentDescription = stringResource(Res.string.chat_media_close_named, media.name), onClick = onClose, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd))
            if (state.phase != PlaybackPhase.Loading) {
                val (icon, words) = when (state.phase) {
                    PlaybackPhase.Playing -> Lucide.Pause to Res.string.chat_media_pause_named
                    PlaybackPhase.Ended -> Lucide.RotateCcw to Res.string.chat_media_replay_named
                    PlaybackPhase.Failed -> Lucide.RotateCcw to Res.string.chat_media_retry_named
                    else -> Lucide.Play to Res.string.chat_media_play_named
                }
                val description = stringResource(words, media.name)
                IconButton(
                    icon,
                    contentDescription = description,
                    onClick = onToggle,
                    tint = Color.White,
                    containerColor = Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.Center).clip(CircleShape),
                )
            }
        }
    }
}

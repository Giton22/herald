package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Open/closed state of a [SidebarLayout]. The drawer (phones) and the docked panel (wide screens) keep
 * separate memories: the drawer starts closed and the docked panel starts out, so switching between
 * them, e.g. on rotation, never leaves a drawer covering the chat.
 */
@Stable
class SidebarState internal constructor() {

    /** 0 = closed, 1 = open; follows the finger while dragging. */
    internal val progress = Animatable(0f)

    private var drawerOpen by mutableStateOf(false)
    private var dockedOpen by mutableStateOf(true)

    /** True when the layout is wide enough to dock the sidebar beside the content instead of over it. */
    var docked by mutableStateOf(false)
        private set

    /** Where the sidebar is headed (or rests) in the current mode, regardless of the running animation. */
    val isOpen: Boolean get() = if (docked) dockedOpen else drawerOpen

    /** How far open it is right now, 0 to 1; follows the finger while dragging. */
    val fraction: Float get() = progress.value

    suspend fun open() = settle(true)

    suspend fun close() = settle(false)

    suspend fun toggle() = settle(!isOpen)

    internal suspend fun settle(open: Boolean) {
        if (docked) dockedOpen = open else drawerOpen = open
        progress.animateTo(if (open) 1f else 0f, spring(stiffness = 600f))
    }

    internal suspend fun dragBy(fraction: Float) {
        progress.snapTo((progress.value + fraction).coerceIn(0f, 1f))
    }

    internal suspend fun updateDocked(value: Boolean) {
        docked = value
        progress.snapTo(if (isOpen) 1f else 0f)
    }
}

@Composable
fun rememberSidebarState(): SidebarState = remember { SidebarState() }

/**
 * A left sidebar next to the main [content]. Below [dockedBreakpoint] it is a drawer that slides in
 * over a dimmed content area (swipe right anywhere to open, swipe left or tap outside to close); from
 * the breakpoint up it is docked beside the content and collapses to zero width when closed.
 */
@Composable
fun SidebarLayout(
    state: SidebarState,
    sidebar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dockedBreakpoint: Dp = 840.dp,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val docked = maxWidth >= dockedBreakpoint
        LaunchedEffect(docked) { state.updateDocked(docked) }
        val width = if (docked) 304.dp else min(maxWidth * 0.86f, 340.dp)
        val p = state.progress.value
        val hidden = Modifier.clearAndSetSemantics { }.takeIf { !state.isOpen && p == 0f } ?: Modifier

        if (docked) {
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .width(width * p)
                        .fillMaxHeight()
                        .clipToBounds()
                        .background(Theme[colors][surface])
                        .then(hidden),
                ) {
                    Box(Modifier.requiredWidth(width).fillMaxHeight().align(Alignment.CenterEnd)) { sidebar() }
                }
                if (p > 0f) Box(Modifier.width(1.dp).fillMaxHeight().background(Theme[colors][stroke]))
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
            return@BoxWithConstraints
        }

        val widthPx = with(LocalDensity.current) { width.toPx() }
        val scope = rememberCoroutineScope()
        val dragState = rememberDraggableState { delta -> scope.launch { state.dragBy(delta / widthPx) } }
        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val open = when {
                            velocity > FLING_VELOCITY -> true
                            velocity < -FLING_VELOCITY -> false
                            else -> state.progress.value > 0.5f
                        }
                        state.settle(open)
                    },
                ),
        ) {
            // The content slides along with the drawer, like a push, and dims as it opens.
            Box(Modifier.fillMaxSize().offset { IntOffset((widthPx * p).roundToInt(), 0) }) {
                content()
                if (p > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f * p))
                            .pointerInput(Unit) { detectTapGestures { scope.launch { state.close() } } },
                    )
                }
            }
            Box(
                Modifier
                    .requiredWidth(width)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((-widthPx * (1f - p)).roundToInt(), 0) }
                    .background(Theme[colors][surface])
                    .then(hidden),
            ) { sidebar() }
        }
    }
}

private const val FLING_VELOCITY = 800f

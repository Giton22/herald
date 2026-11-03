package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.sidebar as sidebarColor
import dev.hermeskotlin.designsystem.stroke
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Open/closed state of a [SidebarLayout]. The drawer (phones) and the docked panel (wide screens) keep
 * separate memories: the drawer starts closed and the docked panel starts out, so switching between
 * them, e.g. on rotation, never leaves a drawer covering the chat.
 */
@Stable
class SidebarState internal constructor() {

    // 0 = closed, 1 = open. Drags write it synchronously, so a late drag delta can never cancel the
    // settle animation that starts when the finger lifts and strand the drawer half open.
    private var progress by mutableFloatStateOf(0f)
    private val motion = MutatorMutex()

    private var drawerOpen by mutableStateOf(false)
    private var dockedOpen by mutableStateOf(true)

    /** True when the layout is wide enough to dock the sidebar beside the content instead of over it. */
    var docked by mutableStateOf(false)
        private set

    /** Where the sidebar is headed (or rests) in the current mode, regardless of the running animation. */
    val isOpen: Boolean get() = if (docked) dockedOpen else drawerOpen

    /** How far open it is right now, 0 to 1; follows the finger while dragging. */
    val fraction: Float get() = progress

    suspend fun open() = settle(true)

    suspend fun close() = settle(false)

    suspend fun toggle() = settle(!isOpen)

    internal suspend fun settle(open: Boolean) {
        if (docked) dockedOpen = open else drawerOpen = open
        motion.mutate {
            animate(progress, if (open) 1f else 0f, animationSpec = spring(stiffness = 600f)) { value, _ -> progress = value }
        }
    }

    /** A new drag grabs the drawer mid-animation. */
    internal suspend fun stopAnimation() = motion.mutate(MutatePriority.UserInput) {}

    internal fun dragBy(fraction: Float) {
        progress = (progress + fraction).coerceIn(0f, 1f)
    }

    internal suspend fun updateDocked(value: Boolean) {
        docked = value
        motion.mutate { progress = if (isOpen) 1f else 0f }
    }
}

@Composable
fun rememberSidebarState(): SidebarState = remember { SidebarState() }

/**
 * A left sidebar next to the main [content]. Where there is room it is docked beside the content and collapses to
 * zero width when closed; elsewhere it is a drawer that slides in over a dimmed content area (swipe right anywhere to
 * open, swipe left or tap outside to close). There is room from [dockedBreakpoint] up when one is given (a desktop's),
 * and otherwise where [sidebarDocks] says so.
 *
 * Docked, it is [dockedWidth] wide. With [onDockedWidthChange] its edge drags to another width (a desktop's), which
 * is handed over when the drag ends; a double-click on the edge goes back to the usual width.
 */
@Composable
fun SidebarLayout(
    state: SidebarState,
    sidebar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dockedBreakpoint: Dp? = null,
    dockedWidth: Dp = DOCKED_WIDTH,
    onDockedWidthChange: ((Dp) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        // A side navigation bar or a cutout. Not safeDrawing: its keyboard part would recompose this on every frame
        // the keyboard moves, and it has no sides.
        val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val (startInset, endInset) = with(density) {
            val left = insets.getLeft(this, layoutDirection).toDp()
            val right = insets.getRight(this, layoutDirection).toDp()
            if (layoutDirection == LayoutDirection.Ltr) left to right else right to left
        }
        val docked = dockedBreakpoint?.let { maxWidth >= it }
            ?: sidebarDocks(maxWidth, maxHeight, startInset + endInset, dockedWidth)
        LaunchedEffect(docked) { state.updateDocked(docked) }
        // While the edge is dragged, the width it's at; the chat keeps at least as much room as the sidebar's least.
        var dragged by remember { mutableStateOf<Dp?>(null) }
        val most = maxOf(MIN_DOCKED_WIDTH, minOf(MAX_DOCKED_WIDTH, maxWidth - MIN_DOCKED_WIDTH))
        val width = if (docked) (dragged ?: dockedWidth).coerceIn(MIN_DOCKED_WIDTH, most) else min(maxWidth * 0.86f, 340.dp)
        val p = state.fraction
        val hidden = Modifier.clearAndSetSemantics { }.takeIf { !state.isOpen && p == 0f } ?: Modifier

        if (docked) {
            // The docked sidebar takes the start inset on top of its width, as it pads itself by it, and the content
            // beside it doesn't pad by it again.
            val outer = width + startInset
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .width(outer * p)
                        .fillMaxHeight()
                        .clipToBounds()
                        .background(Theme[colors][sidebarColor])
                        .then(hidden),
                ) {
                    Box(Modifier.requiredWidth(outer).fillMaxHeight().align(Alignment.CenterEnd)) { sidebar() }
                }
                if (p > 0f) Box(Modifier.width(DIVIDER_WIDTH).fillMaxHeight().background(Theme[colors][stroke]))
                // Only as much as the sidebar covers right now, so the content doesn't jump as it slides open or shut.
                val covered = if (p > 0f) min(startInset, outer * p + DIVIDER_WIDTH) else 0.dp
                val consumed = if (layoutDirection == LayoutDirection.Ltr) WindowInsets(left = covered) else WindowInsets(right = covered)
                Box(Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(consumed)) { content() }
            }
            if (onDockedWidthChange != null && p == 1f) {
                val density = LocalDensity.current
                // The hairline is the edge to grab, with a few pixels either side of it to aim at.
                Box(
                    Modifier
                        .offset(x = outer - EDGE_GRIP / 2)
                        .width(EDGE_GRIP)
                        .fillMaxHeight()
                        .pointerHoverIcon(ResizeHorizontalPointer)
                        .pointerInput(onDockedWidthChange) {
                            detectTapGestures(onDoubleTap = { onDockedWidthChange(DOCKED_WIDTH) })
                        }
                        .draggable(
                            state = rememberDraggableState { delta ->
                                dragged = ((dragged ?: width) + with(density) { delta.toDp() }).coerceIn(MIN_DOCKED_WIDTH, most)
                            },
                            orientation = Orientation.Horizontal,
                            onDragStarted = { dragged = width },
                            onDragStopped = {
                                dragged?.let(onDockedWidthChange)
                                dragged = null
                            },
                        )
                        .clearAndSetSemantics { },
                )
            }
            return@BoxWithConstraints
        }

        val widthPx = with(LocalDensity.current) { width.toPx() }
        val scope = rememberCoroutineScope()
        val dragState = rememberDraggableState { delta -> state.dragBy(delta / widthPx) }
        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    // A phone's swipe; a mouse drag over the chat selects text instead of sliding the list in.
                    enabled = !LocalDesktopLayout.current,
                    onDragStarted = { state.stopAnimation() },
                    onDragStopped = { velocity ->
                        val open = when {
                            velocity > FLING_VELOCITY -> true
                            velocity < -FLING_VELOCITY -> false
                            else -> state.fraction > 0.5f
                        }
                        state.settle(open)
                    },
                ),
        ) {
            // The content slides along with the drawer, like a push.
            Box(Modifier.fillMaxSize().offset { IntOffset((widthPx * p).roundToInt(), 0) }) { content() }
            // The dim covers the whole screen behind the drawer, so its rounded corners show dimmed ground too.
            if (p > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f * p))
                        .pointerInput(Unit) { detectTapGestures { scope.launch { state.close() } } },
                )
            }
            val edge = Theme[colors][stroke]
            Box(
                Modifier
                    .requiredWidth(width)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((-widthPx * (1f - p)).roundToInt(), 0) }
                    // A sheet over the chat, its outer corners rounded.
                    .clip(DrawerShape)
                    .background(Theme[colors][sidebarColor], DrawerShape)
                    // The dim alone doesn't show the edge when both are black (pure black theme): a hairline
                    // along the end and its corners only, none along the screen's own edges.
                    .drawWithContent {
                        drawContent()
                        val r = DRAWER_RADIUS.toPx()
                        val s = 1.dp.toPx()
                        val w = size.width - s / 2
                        val h = size.height
                        val path = Path().apply {
                            moveTo(w - r, s / 2)
                            arcTo(Rect(w - 2 * r, s / 2, w, 2 * r), -90f, 90f, false)
                            lineTo(w, h - r)
                            arcTo(Rect(w - 2 * r, h - 2 * r, w, h - s / 2), 0f, 90f, false)
                        }
                        drawPath(path, edge, style = Stroke(s))
                    }
                    .then(hidden),
            ) {
                sidebar()
            }
        }
    }
}

/**
 * Whether the sidebar docks in a window [width] by [height]. A window from [WIDE_WIDTH] always docks it. A narrower
 * one docks it when the content beside a [sidebarWidth] sidebar still gets a phone's width, so an unfolded foldable
 * or a tablet held upright docks too. A phone on its side doesn't: it's too short for a sidebar that stays open.
 * [sideInsets] (a side navigation bar, a cutout) come off the content's width.
 */
internal fun sidebarDocks(width: Dp, height: Dp, sideInsets: Dp, sidebarWidth: Dp = DOCKED_WIDTH): Boolean =
    width >= WIDE_WIDTH ||
        height >= MIN_DOCKED_HEIGHT && width - sideInsets - sidebarWidth - DIVIDER_WIDTH >= MIN_CONTENT_WIDTH

/** The hairline between the docked sidebar and the content. */
private val DIVIDER_WIDTH = 1.dp

/** The narrowest common phone, which every screen already fits, so the chat beside the sidebar is never narrower. */
private val MIN_CONTENT_WIDTH = 360.dp

/** From here up the sidebar docks whatever the height, as it always has: a wide phone on its side, a tablet. */
private val WIDE_WIDTH = 840.dp

/** Below this a window is a phone on its side, and a narrower one keeps the drawer. */
private val MIN_DOCKED_HEIGHT = 480.dp

private const val FLING_VELOCITY = 800f

/** The docked sidebar's usual width, and the least and most its edge drags to. */
val DOCKED_WIDTH = 304.dp
private val MIN_DOCKED_WIDTH = 240.dp
private val MAX_DOCKED_WIDTH = 480.dp

private val EDGE_GRIP = 8.dp

private val DRAWER_RADIUS = 28.dp
private val DrawerShape = RoundedCornerShape(topEnd = DRAWER_RADIUS, bottomEnd = DRAWER_RADIUS)

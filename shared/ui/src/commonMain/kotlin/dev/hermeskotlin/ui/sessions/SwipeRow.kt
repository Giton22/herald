package dev.hermeskotlin.ui.sessions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.inverse
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.onInverse
import dev.hermeskotlin.designsystem.sidebar
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * What a swipe past the threshold does, and how its row goes: off the list ([Archive], which also unarchives)
 * or back in place ([Pin], and [Delete], whose confirmation decides).
 */
internal enum class SwipeKind { Archive, Pin, Delete }

/** One side's action on a [SwipeRow]. */
internal class SwipeAction(val label: String, val icon: ImageVector, val kind: SwipeKind, val onSwipe: () -> Unit)

/**
 * A row that can be swiped sideways: [start] shows under it as it moves right, [end] as it moves left (on
 * screen, in either layout direction). Past the threshold the action fills in and ticks; letting go there
 * does it. [content] puts the modifier it gets on its clickable node, which offers the actions to screen
 * readers, as they can't swipe a row.
 */
@Composable
internal fun SwipeRow(
    start: SwipeAction?,
    end: SwipeAction?,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    if (start == null && end == null) {
        Box(modifier) { content(Modifier) }
        return
    }
    val haptics = LocalHapticFeedback.current
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val minThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val threshold = maxOf(minThreshold, width * THRESHOLD_FRACTION)
    // The drag's handlers outlive recompositions, so they read the latest actions.
    val startAction by rememberUpdatedState(start)
    val endAction by rememberUpdatedState(end)
    val dragState = rememberDraggableState { delta ->
        val was = abs(offset) >= threshold
        val lo = if (endAction != null) -width.toFloat() else 0f
        val hi = if (startAction != null) width.toFloat() else 0f
        offset = (offset + delta).coerceIn(lo, hi)
        if (was != abs(offset) >= threshold) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
    val action = when {
        offset > 0f -> start
        offset < 0f -> end
        else -> null
    }
    val armed = abs(offset) >= threshold
    val (fillToken, onFillToken) = when (action?.kind) {
        SwipeKind.Pin -> accent to onAccent
        SwipeKind.Delete -> danger to onAccent
        else -> inverse to onInverse
    }
    val fill by animateColorAsState(if (armed) Theme[colors][fillToken] else Theme[colors][surface2])
    val tint by animateColorAsState(if (armed) Theme[colors][onFillToken] else Theme[colors][textTertiary])

    // On the row's own clickable node, which is the one a screen reader lands on and lists actions for.
    val actions = Modifier.semantics {
        customActions = listOfNotNull(start, end).map { a -> CustomAccessibilityAction(a.label) { a.onSwipe(); true } }
    }
    Box(modifier.onSizeChanged { width = it.width }) {
        if (action != null) {
            Row(
                Modifier.matchParentSize().clip(shape).background(fill).padding(horizontal = 20.dp).clearAndSetSemantics { },
                // The drag moves the row left or right on screen whatever the layout direction, so the gap is too.
                horizontalArrangement = Arrangement.spacedBy(8.dp, if (offset > 0f) AbsoluteAlignment.Left else AbsoluteAlignment.Right),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnstyledIcon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Text(action.label, style = Theme[typography][caption], color = tint, maxLines = 1)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset }
                // The list's ground under a moving row, so the action shows beside it and not through it.
                .then(if (offset != 0f) Modifier.clip(shape).background(Theme[colors][sidebar]) else Modifier)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    // A new drag takes the row from wherever the last one's settling left it.
                    onDragStarted = { settling?.cancel() },
                    onDragStopped = { velocity ->
                        settling = coroutineContext[Job]
                        val flung = abs(velocity) > FLING_VELOCITY && velocity * offset > 0f && abs(offset) > threshold / 3
                        val done = (abs(offset) >= threshold || flung).let { if (it) (if (offset > 0f) startAction else endAction) else null }
                        if (done?.kind == SwipeKind.Archive) {
                            // Off the row's side, then out of the list; the undo toast brings it back. A drag or
                            // the list cutting the slide short still archives, as the tick promised.
                            try {
                                animate(offset, if (offset > 0f) width.toFloat() else -width.toFloat(), velocity, spring(stiffness = 800f)) { v, _ -> offset = v }
                            } finally {
                                done.onSwipe()
                            }
                            // Left in place, the row would flash back before the list drops it. A row the list
                            // keeps (no gateway to ask) comes back once it's clear it stays.
                            delay(KEPT_ROW_MILLIS)
                            offset = 0f
                        } else {
                            // Done first, so a drag that catches the row on its way back can't drop it.
                            done?.onSwipe()
                            animate(offset, 0f, velocity, spring(dampingRatio = 0.8f, stiffness = 600f)) { v, _ -> offset = v }
                        }
                    },
                ),
        ) {
            content(actions)
        }
    }
}

/** How far across the row a swipe must go to count, as a share of its width (with 72dp at the least). */
private const val THRESHOLD_FRACTION = 0.35f

/** A flick this fast (px/s) toward an action does it even short of the threshold. */
private const val FLING_VELOCITY = 1500f

/** How long an archived row waits off to the side for the list to drop it before it slides back. */
private const val KEPT_ROW_MILLIS = 600L

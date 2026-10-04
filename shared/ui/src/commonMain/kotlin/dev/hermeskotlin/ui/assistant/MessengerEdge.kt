package dev.hermeskotlin.ui.assistant

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.core.chat.ChatState
import kotlinx.coroutines.flow.emptyFlow

/** What the edge is saying: the assistant is here, hearing you, at work, or done. */
internal enum class EdgeMood { Idle, Listening, Working, Answered }

internal fun edgeMood(recording: Boolean, transcribing: Boolean, running: Boolean, answered: Boolean): EdgeMood = when {
    recording -> EdgeMood.Listening
    transcribing || running -> EdgeMood.Working
    answered -> EdgeMood.Answered
    else -> EdgeMood.Idle
}

/**
 * How lit the edge is at [t] (0..1 around it) with messengers at [heads]: a runner's bright head and a
 * fading trail [tail] long behind it, the way Hermes carries a message round. 0 is the unlit edge.
 */
internal fun trailIntensity(t: Float, heads: List<Float>, tail: Float): Float {
    var lit = 0f
    for (head in heads) {
        val behind = ((head - t) % 1f + 1f) % 1f
        if (behind < tail) {
            val fade = 1f - behind / tail
            lit = maxOf(lit, fade * fade)
        }
    }
    return lit
}

/**
 * Herald's answer to the assistant glow: Nous blue light running round the screen's edge. It strolls
 * while the panel waits, swells with your voice while listening, races while Hermes works, and settles
 * to a still rim once the answer is in. Drawn over the app behind; it takes no touches.
 */
@Composable
internal fun MessengerEdge(model: AssistantPanelModel, modifier: Modifier = Modifier) {
    val dictation by model.voice.dictation.collectAsState()
    val session by model.session.collectAsState()
    val state by (session?.state ?: remember { emptyFlow() }).collectAsState(ChatState())
    val mood = edgeMood(dictation.recording, dictation.transcribing, state.running, state.hasConversation && !state.running)

    // Laps per second; the brightness of the trail; how much of the unlit rim still shows.
    val speed by animateFloatAsState(
        when (mood) {
            EdgeMood.Idle -> 0.12f
            EdgeMood.Listening -> 0.2f
            EdgeMood.Working -> 0.55f
            EdgeMood.Answered -> 0.04f
        },
        tween(600),
    )
    val voice by animateFloatAsState(if (mood == EdgeMood.Listening) dictation.level else 0f, tween(120))
    val glow by animateFloatAsState(
        when (mood) {
            EdgeMood.Idle -> 0.7f
            EdgeMood.Listening -> 0.6f + 0.4f * voice
            EdgeMood.Working -> 1f
            EdgeMood.Answered -> 0.35f
        },
        tween(400),
    )
    val rim by animateFloatAsState(if (mood == EdgeMood.Answered) 0.45f else 0.18f, tween(600))

    val phase = remember { mutableFloatStateOf(0f) }
    val currentSpeed by rememberUpdatedState(speed)
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                phase.floatValue = (phase.floatValue + (now - last) / 1e9f * currentSpeed) % 1f
                last = now
            }
        }
    }

    Canvas(modifier) {
        val heads = listOf(phase.floatValue, (phase.floatValue + 0.5f) % 1f)
        val stops = Array(STOPS + 1) { i ->
            val t = i / STOPS.toFloat()
            val lit = trailIntensity(t, heads, TAIL) * glow
            val color = if (lit > 0.6f) lerp(EdgeBright, EdgeHead, (lit - 0.6f) / 0.4f) else lerp(Nous, EdgeBright, lit / 0.6f)
            t to color.copy(alpha = (rim + (1f - rim) * lit).coerceIn(0f, 1f))
        }
        val brush = Brush.sweepGradient(*stops, center = center)
        val corner = CornerRadius(CORNER.toPx())
        // A soft wide halo under a crisp line, both centred on the screen's edge so only their inner half shows.
        for ((width, alpha) in LAYERS) {
            val w = width.toPx() * (1f + 0.6f * voice)
            drawRoundRect(brush, topLeft = Offset.Zero, size = Size(size.width, size.height), cornerRadius = corner, style = Stroke(w), alpha = alpha)
        }
    }
}

private val Nous = Color(0xFF0053FD)
/** The trail's light blue and its near-white head; the circling stroke is drawn in the same light. */
internal val EdgeBright = Color(0xFF4A84FE)
internal val EdgeHead = Color(0xFFD6E4FF)
private const val STOPS = 72
private const val TAIL = 0.32f
private val CORNER = 36.dp
private val LAYERS = listOf(28.dp to 0.18f, 12.dp to 0.4f, 4.dp to 1f)

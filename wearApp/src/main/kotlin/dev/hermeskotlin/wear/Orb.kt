package dev.hermeskotlin.wear

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** What the orb shows. */
enum class OrbMood {
    /** Waiting to be asked: it breathes. */
    Idle,

    /** Hearing you: the bars and the outer ring follow your voice. */
    Listening,

    /** Hermes works: an arc circles it. */
    Working,

    /** The reply is read aloud: the bars wave. */
    Speaking,

    /** Hermes waits on an answer given on the phone: amber, with the arc. */
    Waiting,

    /** Something went wrong: red and still. */
    Failed,
}

val HeraldBlue = Color(0xFF2F6BF5)
val HeraldBlueText = Color(0xFF7AA5FF)
val Amber = Color(0xFFF5A524)
val Danger = Color(0xFFF85149)

/** The color the orb and the halo take for [mood]. */
fun orbTint(mood: OrbMood, accent: Color = HeraldBlue): Color = when (mood) {
    OrbMood.Waiting -> Amber
    OrbMood.Failed -> Danger
    else -> accent
}

/**
 * Herald's voice orb, as on the phone's voice screen: a lit ball with five bars inside two soft rings. The outer
 * ring breathes and swells with your voice; the bars rise as you talk and wave while the reply is read; an arc
 * circles it while Hermes works. A thin ring at the rim fills clockwise with how full the chat's [context] is
 * (0–1, null: not known yet), amber then red as it nears the end, when it's time for a new chat.
 */
@Composable
fun HeraldOrb(mood: OrbMood, level: Float, modifier: Modifier = Modifier, context: Float? = null) {
    val fill by animateFloatAsState(context?.coerceIn(0f, 1f) ?: 0f, tween(600), label = "context")
    val accentText = MaterialTheme.colorScheme.primary
    val tint by animateColorAsState(orbTint(mood, MaterialTheme.colorScheme.primaryContainer), label = "tint")
    val time by rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_200, easing = LinearEasing)),
        label = "time",
    )
    val listening = mood == OrbMood.Listening
    // Square root, so a watch mic's quiet range still visibly moves it.
    val voice by animateFloatAsState(
        if (listening) sqrt((level * 4f).coerceIn(0f, 1f)) else 0f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "level",
    )
    val circling = mood == OrbMood.Working || mood == OrbMood.Waiting
    Canvas(modifier) {
        val outer = size.minDimension / 2f
        val wave = sin(time * 2f * PI.toFloat())
        val soft = tint.copy(alpha = 0.16f)
        drawCircle(soft, radius = outer * (0.9f + 0.05f * wave + 0.1f * voice))
        drawCircle(soft, radius = outer * 0.76f)
        val core = outer * 0.6f
        // The glow under the ball, a little low, as if lit from above.
        val glowCenter = Offset(center.x, center.y + core * 0.18f)
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.5f), Color.Transparent), center = glowCenter, radius = core * 1.4f), radius = core * 1.4f, center = glowCenter)
        drawCircle(
            Brush.radialGradient(listOf(lerp(tint, Color.White, 0.45f), tint), center = Offset(center.x - core * 0.3f, center.y - core * 0.4f), radius = core * 1.3f),
            radius = core,
        )
        val bar = core * 0.12f
        val gap = core * 0.12f
        val unit = core / 40f
        BAR_HEIGHTS.forEachIndexed { i, full ->
            val scale = when (mood) {
                OrbMood.Speaking -> 0.45f + 0.55f * ((sin((time * 2f + i * 0.21f) * 2f * PI.toFloat()) + 1f) / 2f)
                OrbMood.Listening -> 0.3f + 0.7f * voice
                OrbMood.Idle -> 0.3f + 0.08f * ((wave + 1f) / 2f)
                else -> 0.3f
            }
            val h = full * unit * scale
            val x = center.x + (i - 2) * (bar + gap) - bar / 2
            drawRoundRect(Color.White.copy(alpha = BAR_ALPHAS[i]), topLeft = Offset(x, center.y - h / 2), size = Size(bar, h), cornerRadius = CornerRadius(bar / 2))
        }
        if (context != null) {
            val width = 2.dp.toPx()
            // Inside the rim: the orb is clipped to its circle.
            val rim = outer - width / 2f - 0.5.dp.toPx()
            val topLeft = Offset(center.x - rim, center.y - rim)
            val box = Size(rim * 2, rim * 2)
            drawCircle(Color.White.copy(alpha = 0.1f), radius = rim, style = Stroke(width))
            if (fill > 0f) {
                drawArc(contextTint(fill, accentText), startAngle = -90f, sweepAngle = 360f * fill, useCenter = false, topLeft = topLeft, size = box, style = Stroke(width, cap = StrokeCap.Round))
            }
        }
        if (circling) {
            val ring = outer * 0.84f
            rotate(time * 360f) {
                drawArc(
                    lerp(tint, Color.White, 0.35f),
                    startAngle = 0f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(center.x - ring, center.y - ring),
                    size = Size(ring * 2, ring * 2),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** The context ring's color: the accent, amber from three quarters full, red from nine tenths. */
internal fun contextTint(fill: Float, accentText: Color = HeraldBlueText): Color = when {
    fill >= 0.9f -> Danger
    fill >= 0.75f -> Amber
    else -> accentText
}

private val BAR_HEIGHTS = listOf(18, 34, 24, 40, 20)
private val BAR_ALPHAS = listOf(0.8f, 1f, 0.9f, 1f, 0.8f)

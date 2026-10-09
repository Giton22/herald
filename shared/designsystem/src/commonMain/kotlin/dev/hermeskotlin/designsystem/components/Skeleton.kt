package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface3
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * A grey stand-in for content on its way, with a light sweeping across it. Screen readers skip it; the screen
 * says it's loading some other way.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    val base = Theme[colors][surface]
    val light = Theme[colors][surface3]
    val sweep by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    Box(
        modifier
            .clearAndSetSemantics {}
            .clip(shape)
            .drawBehind {
                val x = size.width * sweep
                drawRect(
                    Brush.linearGradient(
                        listOf(base, light, base),
                        start = Offset(x - size.width, 0f),
                        end = Offset(x + size.width / 2, 0f),
                    ),
                )
            },
    )
}

private const val SWEEP_MILLIS = 1600

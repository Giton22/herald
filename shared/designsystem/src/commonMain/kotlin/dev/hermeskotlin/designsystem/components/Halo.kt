package dev.hermeskotlin.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.colors

/**
 * The one soft blue glow behind the top of a screen, brightest near its top-left corner and gone well before
 * the content. Every screen's ground carries one; nothing else on it is a gradient.
 */
@Composable
fun Modifier.halo(): Modifier {
    val glow = Theme[colors][accentSoft]
    return drawBehind {
        val height = minOf(size.height, HALO_HEIGHT.toPx())
        drawRect(
            Brush.radialGradient(
                0f to glow,
                0.7f to Color.Transparent,
                center = Offset(size.width * 0.15f, 0f),
                radius = maxOf(size.width * 0.8f, height),
            ),
            size = Size(size.width, height),
        )
    }
}

private val HALO_HEIGHT = 260.dp

package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surfaceElevated

/**
 * Panels. Flat by default (fill + one hairline). [elevated] is for floating panels only:
 * a soft downward shadow plus the hairline — never thick borders or nested boxes.
 */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    color: Color = if (elevated) Theme[colors][surfaceElevated] else Theme[colors][surface],
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val shadow = if (elevated) {
        Modifier.shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f))
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(shadow)
            .clip(shape)
            .background(color)
            .border(1.dp, Theme[colors][stroke], shape),
        content = content,
    )
}

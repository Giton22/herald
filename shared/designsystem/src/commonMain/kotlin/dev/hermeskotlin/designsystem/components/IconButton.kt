package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.rememberHoverIndication
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.textSecondary

/** A 48dp square ([MinTouchTarget]), icon-only button for toolbars and rows. [contentDescription] is required for accessibility. */
@Composable
fun IconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Theme[colors][textSecondary],
    /** Fill behind the icon; transparent for toolbar use, the accent for a primary action like Send. */
    containerColor: Color = Color.Transparent,
    iconSize: Dp = 20.dp,
) {
    val size = MinTouchTarget
    val desktop = LocalDesktopLayout.current
    // A desktop's toolbar button is a tighter square with a smaller glyph, as its 36dp size suits.
    val shape = RoundedCornerShape(if (desktop) 8.dp else Theme[radii][radiusMedium])
    val glyph = if (desktop && iconSize == 20.dp) 18.dp else iconSize
    val button = @Composable { outer: Modifier ->
        UnstyledButton(
            onClick = onClick,
            enabled = enabled,
            modifier = outer.handCursor(enabled).size(size).clip(shape).background(containerColor).alpha(if (enabled) 1f else 0.45f),
            // On a filled button the focus ring is in the icon's color, which shows on the fill.
            indication = rememberHoverIndication(tint, ring = if (containerColor.alpha > 0f) tint else Theme[colors][accent], shape = shape),
        ) {
            UnstyledIcon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(glyph))
        }
    }
    // On the desktop the button names itself on hover, as there's no long-press to ask. The caller's modifier
    // goes on the outermost node, so an align or weight still reaches the parent.
    if (LocalDesktopLayout.current) Tooltip(contentDescription, modifier) { button(Modifier) } else button(modifier)
}

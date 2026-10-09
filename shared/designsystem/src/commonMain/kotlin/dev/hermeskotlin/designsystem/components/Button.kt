package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.ProvideContentColor
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.inverse
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.onInverse
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.typography

/** [Inverse] is the text color filled in, for a strong action that isn't the screen's one primary, like Run now. */
enum class ButtonVariant { Primary, Secondary, Outline, Ghost, Danger, Inverse }

enum class ButtonSize(val height: Dp, val horizontalPadding: Dp, val iconSize: Dp, val fontSize: TextUnit) {
    Small(36.dp, 14.dp, 15.dp, 13.sp),
    Medium(44.dp, 18.dp, 16.dp, 14.sp),
    Large(52.dp, 22.dp, 16.dp, 15.sp),
}

/**
 * The one button: always a pill. Style comes from [variant] and [size]; call sites don't restyle it.
 * A large primary button, the screen's main action, glows in the accent under it.
 */
@Composable
fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Medium,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    /** An icon after the label instead, like the arrow on "Continue". */
    trailingIcon: ImageVector? = null,
) {
    val shape = CircleShape
    val (container, content) = when (variant) {
        ButtonVariant.Primary -> Theme[colors][accent] to Theme[colors][onAccent]
        ButtonVariant.Secondary -> Theme[colors][surface3] to Theme[colors][textColor]
        ButtonVariant.Outline -> Color.Transparent to Theme[colors][textColor]
        ButtonVariant.Ghost -> Color.Transparent to Theme[colors][textColor]
        ButtonVariant.Danger -> Theme[colors][danger] to Color.White
        ButtonVariant.Inverse -> Theme[colors][inverse] to Theme[colors][onInverse]
    }
    val outline = if (variant == ButtonVariant.Outline) {
        Modifier.border(1.dp, Theme[colors][strokeStrong], shape)
    } else {
        Modifier
    }
    val glow = if (variant == ButtonVariant.Primary && size == ButtonSize.Large && enabled) {
        Modifier.glow(shape, Shadow(radius = 18.dp, color = container.copy(alpha = 0.35f), offset = DpOffset(0.dp, 6.dp)))
    } else {
        Modifier
    }
    val main = variant == ButtonVariant.Primary || variant == ButtonVariant.Danger || variant == ButtonVariant.Inverse

    UnstyledButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .defaultMinSize(minHeight = size.height)
            .alpha(if (enabled) 1f else 0.45f)
            .then(glow)
            .then(outline)
            // Clipped after the glow, so the glow spreads past the pill and the press stays inside it.
            .clip(shape)
            .background(container, shape),
        indication = rememberColoredIndication(content),
        contentPadding = PaddingValues(horizontal = size.horizontalPadding),
    ) {
        ProvideContentColor(content) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    loading -> Spinner(Modifier.size(size.iconSize), color = content)
                    leadingIcon != null -> UnstyledIcon(leadingIcon, contentDescription = null, modifier = Modifier.size(size.iconSize), tint = content)
                }
                Text(
                    text,
                    style = Theme[typography][label].copy(
                        fontSize = size.fontSize,
                        fontWeight = if (main) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    // Large text wraps to a second line inside the pill rather than running out of it.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (trailingIcon != null && !loading) {
                    UnstyledIcon(trailingIcon, contentDescription = null, modifier = Modifier.size(size.iconSize), tint = content)
                }
            }
        }
    }
}

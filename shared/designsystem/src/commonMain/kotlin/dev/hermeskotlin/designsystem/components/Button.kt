package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.ProvideContentColor
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusFull
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.typography

enum class ButtonVariant { Primary, Secondary, Outline, Ghost, Danger }

enum class ButtonSize(val height: Dp, val horizontalPadding: Dp, val iconSize: Dp) {
    Small(36.dp, 12.dp, 16.dp),
    Medium(44.dp, 16.dp, 18.dp),
    Large(52.dp, 20.dp, 20.dp),
}

/** The one button. Style comes from [variant] and [size]; call sites don't restyle it. */
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
    /** Fully rounded ends, for a floating action like "New chat". */
    pill: Boolean = false,
) {
    val shape = RoundedCornerShape(Theme[radii][if (pill) radiusFull else radiusMedium])
    val (container, content) = when (variant) {
        ButtonVariant.Primary -> Theme[colors][accent] to Theme[colors][onAccent]
        ButtonVariant.Secondary -> Theme[colors][accentSoft] to Theme[colors][accentText]
        ButtonVariant.Outline -> Color.Transparent to Theme[colors][textColor]
        ButtonVariant.Ghost -> Color.Transparent to Theme[colors][textColor]
        ButtonVariant.Danger -> Theme[colors][danger] to Color.White
    }
    val outline = if (variant == ButtonVariant.Outline) {
        Modifier.border(1.dp, Theme[colors][strokeStrong], shape)
    } else {
        Modifier
    }

    UnstyledButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .defaultMinSize(minHeight = size.height)
            .alpha(if (enabled) 1f else 0.45f)
            .then(outline)
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
                    leadingIcon != null -> UnstyledIcon(
                        leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(size.iconSize),
                        tint = content,
                    )
                }
                Text(text, style = Theme[typography][label], singleLine = true)
            }
        }
    }
}

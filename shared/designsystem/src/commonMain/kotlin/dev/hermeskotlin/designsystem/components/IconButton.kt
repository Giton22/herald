package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.textSecondary

/** A 40dp round, icon-only button for toolbars and rows. [contentDescription] is required for accessibility. */
@Composable
fun IconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Theme[colors][textSecondary],
) {
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(40.dp).clip(CircleShape).alpha(if (enabled) 1f else 0.45f),
        indication = rememberColoredIndication(tint),
    ) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

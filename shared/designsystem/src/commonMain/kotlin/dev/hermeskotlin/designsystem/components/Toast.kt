package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.inverse
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.onInverse
import dev.hermeskotlin.designsystem.typography

/**
 * A short confirmation that floats over the screen: a dark pill (light in dark mode) with an optional [icon] and
 * one optional action such as "Undo" in the accent. The caller places it, shows one at a time and takes it away
 * after a few seconds. Screen readers announce it.
 */
@Composable
fun Toast(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    /** Shows ×, for a toast that stays until it's closed (as it must while a screen reader is on). */
    onDismiss: (() -> Unit)? = null,
    /** What a screen reader announces instead of [message], e.g. with the chat's name. */
    spokenMessage: String = message,
) {
    val on = Theme[colors][onInverse]
    // A pill for one line; a message that wraps keeps the same corners instead of a half-height radius.
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .widthIn(max = 480.dp)
            .dropShadow(shape, Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.22f), offset = DpOffset(0.dp, 8.dp)))
            .background(Theme[colors][inverse], shape)
            // The action and × are full touch targets, which set the pill's height; the action pill sits 6dp from the end,
            // × at its own edge, and plain text keeps clear of it.
            .padding(start = 16.dp, end = if (onDismiss != null) 0.dp else if (actionLabel != null) 6.dp else 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) UnstyledIcon(icon, contentDescription = null, tint = on, modifier = Modifier.size(16.dp))
        Text(
            message,
            style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.Medium),
            color = on,
            // Fills a full-width toast, so the action sits at its end.
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .semantics { contentDescription = spokenMessage },
        )
        if (actionLabel != null) {
            // A 36dp pill inset in the toast, inside a full-height touch target.
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .defaultMinSize(minHeight = MinTouchTarget)
                    .clickable(interaction, indication = null, role = Role.Button, onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .clip(CircleShape)
                        .background(Theme[colors][accent], CircleShape)
                        .indication(interaction, rememberColoredIndication(Theme[colors][onAccent]))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(actionLabel, style = Theme[typography][label].copy(fontWeight = FontWeight.SemiBold), color = Theme[colors][onAccent])
                }
            }
        }
        if (onDismiss != null) IconButton(Lucide.X, contentDescription = "Dismiss", onClick = onDismiss, tint = on.copy(alpha = 0.7f), iconSize = 16.dp)
    }
}

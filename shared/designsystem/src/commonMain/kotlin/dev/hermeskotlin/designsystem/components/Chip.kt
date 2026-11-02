package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.rememberHoverIndication
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.inverse
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onInverse
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/**
 * A filter toggle: a ringed pill, filled with the inverse colour when selected. [count] follows the label
 * in mono, in [countColor] (e.g. warning for what needs the user) while unselected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    countColor: Color = Theme[colors][textTertiary],
    /** A long press, such as for the chip's options; [onLongClickLabel] says what it does. Null: none. */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val shape = CircleShape
    val content = if (selected) Theme[colors][onInverse] else Theme[colors][textSecondary]
    val interaction = remember { MutableInteractionSource() }
    val desktop = LocalDesktopLayout.current
    // A 32dp pill drawn inside a full-height touch target; on the desktop a 28dp one, rows of them packed closer.
    Box(
        modifier
            .heightIn(min = if (desktop) 32.dp else MinTouchTarget)
            .handCursor()
            // A mouse has no long press to speak of: the right button opens the chip's options on the desktop.
            .then(
                if (onLongClick == null || !desktop) {
                    Modifier
                } else {
                    Modifier.pointerInput(onLongClick) {
                        awaitEachGesture {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                onLongClick()
                            }
                        }
                    }
                },
            )
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(interaction, indication = null, role = Role.Tab, onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Tab,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                },
            )
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = if (desktop) 28.dp else 32.dp)
                .clip(shape)
                .border(1.dp, if (selected) Color.Transparent else Theme[colors][stroke], shape)
                .background(Theme[colors][if (selected) inverse else surface2], shape)
                .indication(interaction, rememberHoverIndication(content, shape))
                .padding(horizontal = if (desktop) 11.dp else 12.dp, vertical = if (desktop) 3.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = Theme[typography][label].copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                color = content,
                singleLine = true,
            )
            if (count != null) {
                Text(
                    count.toString(),
                    style = Theme[typography][code].copy(fontSize = 12.sp),
                    color = if (selected) content else countColor,
                    singleLine = true,
                )
            }
        }
    }
}

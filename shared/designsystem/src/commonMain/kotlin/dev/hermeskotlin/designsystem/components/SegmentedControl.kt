package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.thumb
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.well

/**
 * Equal-width options in a recessed pill track; the selected one sits on a raised thumb. Each option is a
 * full-height touch target, with an optional [optionIcon] before its label.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    optionIcon: (T) -> ImageVector? = { null },
    /** A count beside the label, in mono, such as how many each tab holds. */
    optionBadge: (T) -> String? = { null },
) {
    val track = Theme[colors][well]
    val ring = Theme[colors][stroke]
    val badges = LocalDensity.current.fontScale <= 1.3f
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .height(IntrinsicSize.Min)
            // The track is drawn 4dp in from the touch area's top and bottom.
            .drawBehind {
                val inset = 4.dp.toPx()
                val h = size.height - inset * 2
                val corner = CornerRadius(h / 2)
                drawRoundRect(track, topLeft = Offset(0f, inset), size = Size(size.width, h), cornerRadius = corner)
                drawRoundRect(ring, topLeft = Offset(0f, inset), size = Size(size.width, h), cornerRadius = corner, style = Stroke(1.dp.toPx()))
            }
            .padding(horizontal = 3.dp)
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val interaction = remember { MutableInteractionSource() }
            val fill by animateColorAsState(if (isSelected) Theme[colors][thumb] else Color.Transparent)
            val edge by animateColorAsState(if (isSelected) Theme[colors][strokeStrong] else Color.Transparent)
            val content = if (isSelected) Theme[colors][text] else Theme[colors][textTertiary]
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = isSelected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(vertical = 7.dp)
                        .clip(CircleShape)
                        .background(fill, CircleShape)
                        .border(1.dp, edge, CircleShape)
                        .indication(interaction, rememberColoredIndication(Theme[colors][text]))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    optionIcon(option)?.let { UnstyledIcon(it, contentDescription = null, tint = content, modifier = Modifier.size(13.dp)) }
                    Text(
                        optionLabel(option),
                        style = Theme[typography][label].copy(fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                        color = content,
                        // Large text wraps to a second line rather than cutting the label off; the track grows.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // A large font leaves the label too little room beside it, and the count is extra.
                    optionBadge(option)?.takeIf { badges }?.let {
                        Text(
                            it,
                            style = Theme[typography][code].copy(fontSize = 11.sp),
                            color = if (isSelected) Theme[colors][textTertiary] else Theme[colors][textMuted],
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

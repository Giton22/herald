package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography

/** Equal-width options in a tinted track; the selected one sits on a raised chip. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Theme[colors][stroke], RoundedCornerShape(Theme[radii][radiusMedium]))
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val fill by animateColorAsState(if (isSelected) Theme[colors][surfaceElevated] else Color.Transparent)
            val shape = RoundedCornerShape(Theme[radii][radiusSmall])
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(shape)
                    .background(fill, shape)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    optionLabel(option),
                    style = Theme[typography][label],
                    color = if (isSelected) Theme[colors][text] else Theme[colors][textSecondary],
                    singleLine = true,
                )
            }
        }
    }
}

package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
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
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    countColor: Color = Theme[colors][textTertiary],
) {
    val shape = CircleShape
    val content = if (selected) Theme[colors][onInverse] else Theme[colors][textSecondary]
    UnstyledButton(
        onClick = onClick,
        role = Role.Tab,
        modifier = modifier
            .heightIn(min = 32.dp)
            .semantics { this.selected = selected }
            .border(1.dp, if (selected) Color.Transparent else Theme[colors][stroke], shape)
            .background(Theme[colors][if (selected) inverse else surface2], shape),
        indication = rememberColoredIndication(content),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
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

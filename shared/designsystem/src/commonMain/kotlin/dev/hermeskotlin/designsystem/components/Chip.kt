package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusFull
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography

/** A pill-shaped filter toggle. Selected chips take the soft accent fill. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusFull])
    val content = if (selected) Theme[colors][accent] else Theme[colors][textSecondary]
    UnstyledButton(
        onClick = onClick,
        role = Role.Tab,
        modifier = modifier
            .height(32.dp)
            .semantics { this.selected = selected }
            .border(1.dp, if (selected) Color.Transparent else Theme[colors][stroke], shape)
            .background(if (selected) Theme[colors][accentSoft] else Color.Transparent, shape),
        indication = rememberColoredIndication(content),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(text, style = Theme[typography][label], color = content, singleLine = true)
    }
}

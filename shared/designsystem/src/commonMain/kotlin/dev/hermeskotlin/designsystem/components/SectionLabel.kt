package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.typography

/** "▦ SESSIONS": a section heading in spaced accent capitals behind a small checker mark. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Theme[colors][accentText]) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckerMark(color)
        Text(text.uppercase(), style = Theme[typography][eyebrow], color = color, singleLine = true)
    }
}

/** A 3×3 checkerboard of tiny squares, solid corners and centre, the rest faint. */
@Composable
fun CheckerMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(9.dp)) {
        val cell = size.width / 3
        for (row in 0 until 3) for (column in 0 until 3) {
            val solid = (row + column) % 2 == 0
            drawRect(
                color = if (solid) color else color.copy(alpha = 0.35f),
                topLeft = Offset(column * cell, row * cell),
                size = Size(cell, cell),
            )
        }
    }
}

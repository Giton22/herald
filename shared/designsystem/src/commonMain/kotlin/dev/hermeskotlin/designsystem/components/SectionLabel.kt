package dev.hermeskotlin.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/** "SESSIONS": a section heading in quiet spaced capitals, read out as written rather than spelled. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Theme[colors][textTertiary]) {
    Text(
        text.uppercase(),
        style = Theme[typography][eyebrow],
        color = color,
        singleLine = true,
        modifier = modifier.semantics {
            heading()
            contentDescription = text
        },
    )
}

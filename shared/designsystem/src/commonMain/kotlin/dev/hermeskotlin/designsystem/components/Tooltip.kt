package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.inverse
import dev.hermeskotlin.designsystem.onInverse
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.typography
import androidx.compose.foundation.background

/**
 * [text] in a small label by [content] while the mouse rests on it, as desktop apps name their icon buttons.
 * Where nothing hovers (a phone), [content] alone. [modifier] is the wrapper's, the node its parent lays out.
 */
@Composable
expect fun Tooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit)

/** The label a [Tooltip] shows. */
@Composable
internal fun TooltipLabel(text: String) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        style = Theme[typography][caption],
        color = Theme[colors][onInverse],
        modifier = Modifier
            .background(Theme[colors][inverse], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text
import androidx.compose.foundation.HorizontalScrollbar as FoundationHorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar as FoundationScrollbar

@Composable
actual fun VerticalScrollbar(state: ScrollState, modifier: Modifier) {
    FoundationScrollbar(rememberScrollbarAdapter(state), modifier, style = scrollbarStyle())
}

@Composable
actual fun VerticalScrollbar(state: LazyListState, modifier: Modifier) {
    FoundationScrollbar(rememberScrollbarAdapter(state), modifier, style = scrollbarStyle())
}

@Composable
actual fun HorizontalScrollbar(state: ScrollState, modifier: Modifier) {
    FoundationHorizontalScrollbar(rememberScrollbarAdapter(state), modifier, style = scrollbarStyle())
}

/** A thin rounded thumb in the text's colour, faint until the mouse is on it. */
@Composable
private fun scrollbarStyle(): ScrollbarStyle {
    val ink = Theme[colors][text]
    return ScrollbarStyle(
        minimalHeight = 32.dp,
        thickness = 6.dp,
        shape = RoundedCornerShape(3.dp),
        hoverDurationMillis = 200,
        unhoverColor = ink.copy(alpha = 0.18f),
        hoverColor = ink.copy(alpha = 0.40f),
    )
}

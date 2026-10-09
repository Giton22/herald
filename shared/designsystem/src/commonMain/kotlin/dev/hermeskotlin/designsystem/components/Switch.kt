package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text

/**
 * An on/off track. It draws only; the row around it owns the click and semantics
 * (`Modifier.toggleable`), so the whole row is one touch target.
 */
@Composable
fun Switch(checked: Boolean, modifier: Modifier = Modifier) {
    // Both tracks are opaque, so the thumb never shows what's behind the switch.
    val track by animateColorAsState(if (checked) Theme[colors][accent] else Theme[colors][surface3])
    val thumb = if (checked) Theme[colors][onAccent] else Theme[colors][text].copy(alpha = 0.7f).compositeOver(track)
    val offset by animateDpAsState(if (checked) 20.dp else 0.dp)
    Box(modifier.size(width = 46.dp, height = 26.dp).background(track, CircleShape).padding(3.dp)) {
        Box(Modifier.offset(x = offset).size(20.dp).background(thumb, CircleShape))
    }
}

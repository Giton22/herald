package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning

enum class Status { Ok, Warning, Error, Neutral }

/** Small colored dot with a label, for connection/health states. */
@Composable
fun StatusDot(status: Status, text: String, modifier: Modifier = Modifier) {
    val color = when (status) {
        Status.Ok -> Theme[colors][success]
        Status.Warning -> Theme[colors][warning]
        Status.Error -> Theme[colors][danger]
        Status.Neutral -> Theme[colors][textTertiary]
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
    }
}

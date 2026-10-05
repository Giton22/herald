package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.typography

/**
 * A short confirmation that floats over the screen, with one optional action such as "Undo". The caller
 * places it, shows one at a time and takes it away after a few seconds. Screen readers announce it.
 */
@Composable
fun Toast(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(modifier.widthIn(max = 480.dp).semantics { liveRegion = LiveRegionMode.Polite }, elevated = true) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = Theme[typography][bodySmall],
                color = Theme[colors][text],
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp, top = 10.dp, bottom = 10.dp),
            )
            if (actionLabel != null) Button(actionLabel, onClick = onAction, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
        }
    }
}

package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composeunstyled.DialogPanel
import com.composeunstyled.Scrim
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDialog
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.title as titleStyle
import dev.hermeskotlin.designsystem.typography

/**
 * Centered modal panel: [title], optional [message], free [content] (e.g. a text field) and a
 * right-aligned row of [actions]. Tapping the scrim or Back calls [onDismissRequest].
 */
@Composable
fun Dialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    message: String? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    UnstyledDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        overlay = { Scrim(scrimColor = Color.Black.copy(alpha = 0.45f), enter = fadeIn(), exit = fadeOut()) },
    ) {
        Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
            DialogPanel(
                paneTitle = title,
                enter = fadeIn() + scaleIn(initialScale = 0.96f),
                exit = fadeOut() + scaleOut(targetScale = 0.96f),
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            ) {
                Surface(Modifier.fillMaxWidth(), elevated = true) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(title, style = Theme[typography][titleStyle], color = Theme[colors][textColor])
                        if (message != null) {
                            Text(message, style = Theme[typography][body], color = Theme[colors][textSecondary])
                        }
                        content()
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            content = actions,
                        )
                    }
                }
            }
        }
    }
}

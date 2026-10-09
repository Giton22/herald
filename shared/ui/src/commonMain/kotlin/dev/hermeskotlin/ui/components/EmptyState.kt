package dev.hermeskotlin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body as bodyStyle
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title as titleStyle
import dev.hermeskotlin.designsystem.typography

/**
 * An empty, error or no-results state: the icon on a tile, a title, what to know, and an optional action,
 * set to the start and centred top to bottom. An [error] tile is tinted red.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    error: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val tile = RoundedCornerShape(20.dp)
    // Scrolls when a short screen or large text can't fit it, so the action is never cut off; centred otherwise.
    // A wide screen keeps the lines readable, the column in the middle.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .then(
                        if (error) {
                            Modifier.background(Theme[colors][dangerSoft], tile)
                        } else {
                            Modifier.background(Theme[colors][surface], tile).border(1.dp, Theme[colors][strokeStrong], tile)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][if (error) danger else accentText], modifier = Modifier.size(28.dp))
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = Theme[typography][titleStyle], color = Theme[colors][text], modifier = Modifier.semantics { heading() })
                Text(body, style = Theme[typography][bodyStyle], color = Theme[colors][textTertiary])
            }
            if (action != null) action()
        }
    }
}

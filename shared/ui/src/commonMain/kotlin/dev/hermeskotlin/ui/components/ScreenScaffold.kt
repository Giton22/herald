package dev.hermeskotlin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.chat.AppMark

/** Single-column, scrollable, inset-aware page with the halo, used by the onboarding-style screens. */
@Composable
fun ScreenScaffold(content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Room at the end for a last button's glow, which reaches about 24dp below it.
                .padding(start = 22.dp, end = 22.dp, top = 36.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            content = content,
        )
    }
}

/**
 * The screen's mark, a large title and a line on what to do. The mark is [icon] on a ringed tile, or the app's
 * own mark, glowing, when there's no icon: the welcome screen.
 */
@Composable
fun ScreenHeader(icon: ImageVector?, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon == null) {
            AppMark(56.dp)
        } else {
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .size(56.dp)
                    .background(Theme[colors][surface2], shape)
                    .border(1.dp, Theme[colors][strokeStrong], shape),
                contentAlignment = Alignment.Center,
            ) {
                UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = Theme[typography][display], color = Theme[colors][text], modifier = Modifier.semantics { heading() })
        Text(subtitle, style = Theme[typography][body].copy(fontSize = 14.5.sp, lineHeight = 22.sp), color = Theme[colors][textSecondary])
    }
}

package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import kotlinx.coroutines.flow.drop
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.sessions.SubpageHeader

/** Settings › Starting instruction: what every new chat tells the agent first. Saved as it's typed. */
@Composable
fun StartInstructionScreen(text: String, onChange: (String) -> Unit, onBack: () -> Unit) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    StartInstructionView(text, onChange, onBack)
}

/** The page itself, stateless but for the field so previews can draw it. */
@Composable
internal fun StartInstructionView(text: String, onChange: (String) -> Unit, onBack: () -> Unit) {
    val field = remember { TextFieldState(text) }
    LaunchedEffect(field) {
        // The first value is the one the page opened with; every edit after it is saved, back to that one too.
        snapshotFlow { field.text.toString() }.drop(1).collect { onChange(it) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader("Starting instruction", onBack = onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Each new chat you start in Herald tells the agent this first. It stays out of the chat, here and on " +
                        "your other Hermes apps. Chats already going keep what they started with. Leave it empty for none.",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                TextField(
                    field,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "What should every new chat know?",
                    singleLine = false,
                    maxLines = 12,
                    clearable = true,
                )
                Text(
                    "Suggestions",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { suggestion ->
                        Button(
                            suggestion,
                            onClick = { field.setTextAndPlaceCursorAtEnd(suggestion) },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
}

private val SUGGESTIONS = listOf(
    "I'm on my phone: keep replies short and skip long tables.",
    "I'm on my phone, often on the go. Answer in a few sentences unless I ask for more.",
)

package dev.hermeskotlin.ui.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Phone
import com.composables.icons.lucide.PhoneOff
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalAccentPalette
import dev.hermeskotlin.designsystem.PureBlack
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import org.koin.compose.koinInject

/**
 * The full screen an approval or a question rings with while the phone is locked or its screen is off: who's
 * calling and why, Answer or Decline. Always dark, as calls are. The question itself isn't shown here, over the
 * lock screen: Answer unlocks first and opens the chat, where it is.
 */
@Composable
fun IncomingCallScreen(caller: String, why: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val settings = koinInject<SettingsStore>().settings.collectAsState().value ?: return
    val palette = AccentPalette.named(settings.accent)
    hermesTheme(palette)(if (settings.pureBlack) PureBlack else ColorScheme.Dark) {
        CompositionLocalProvider(LocalAccentPalette provides palette) {
            IncomingCallView(caller, why, onAnswer, onDecline)
        }
    }
}

/** The screen itself, stateless so previews can draw it. */
@Composable
internal fun IncomingCallView(caller: String, why: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Theme[colors][background]).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Herald call", style = Theme[typography][label], color = Theme[colors][textSecondary])
            Text(
                caller,
                style = Theme[typography][display],
                color = Theme[colors][text],
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(why, style = Theme[typography][body], color = Theme[colors][textSecondary], textAlign = TextAlign.Center)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 72.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CallButton("Decline", Lucide.PhoneOff, Theme[colors][danger], onDecline)
            CallButton("Answer", Lucide.Phone, Theme[colors][accent], onAnswer)
        }
    }
}

@Composable
private fun CallButton(name: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(color).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(icon, contentDescription = name, tint = Theme[colors][onAccent], modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(name, style = Theme[typography][label], color = Theme[colors][text])
    }
}

/** Why it rings, in a few words: what the agent waits for, never the command or the question itself. */
fun callReason(approval: Boolean): String = if (approval) "Needs your approval" else "Has a question for you"

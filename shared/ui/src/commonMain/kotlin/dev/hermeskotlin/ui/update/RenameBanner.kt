package dev.hermeskotlin.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.update.RenameNotice
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.chat.AppMark
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Whether to tell the user the app was renamed, and a way to stop telling them. */
class RenameOffer(val show: Boolean, val onDismiss: () -> Unit)

val LocalRenameOffer = compositionLocalOf { RenameOffer(false) {} }

/** Asks once per launch whether the rename notice is still due (see [RenameNotice]). */
@Composable
fun rememberRenameOffer(): RenameOffer {
    val notice = koinInject<RenameNotice>()
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = notice.pending() }
    return RenameOffer(show) {
        show = false
        scope.launch { notice.dismiss() }
    }
}

/** A card saying Herald is now Kyllene and that nothing else changed, until it is closed. */
@Composable
fun RenameBanner(modifier: Modifier = Modifier) {
    val offer = LocalRenameOffer.current
    if (!offer.show) return
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val tint = Theme[colors][accent]
    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, tint.copy(alpha = 0.4f), shape)
            .background(tint.copy(alpha = 0.08f), shape)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppMark(28.dp, glow = false)
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Herald is now Kyllene", style = Theme[typography][bodySmall], color = Theme[colors][text])
            Text(
                "Same app with a new name and icon. Your sign-in, chats and settings stay.",
                style = Theme[typography][caption],
                color = Theme[colors][textSecondary],
            )
        }
        UnstyledButton(onClick = offer.onDismiss, modifier = Modifier.size(MinTouchTarget).clip(RoundedCornerShape(Theme[radii][radiusSmall]))) {
            UnstyledIcon(Lucide.X, contentDescription = "Got it", tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
        }
    }
}

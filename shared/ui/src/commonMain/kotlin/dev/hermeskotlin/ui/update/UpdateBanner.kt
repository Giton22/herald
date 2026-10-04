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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.update.AppUpdate
import dev.hermeskotlin.core.update.UpdateChecker
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** A newer release, if one was found, and a way to stop offering it. */
class UpdateOffer(val update: AppUpdate?, val onDismiss: () -> Unit)

val LocalUpdateOffer = compositionLocalOf { UpdateOffer(null) {} }

/**
 * Checks once per launch for a newer release of [repo] than [version] (both come from the build;
 * a local build has no repo and never checks), unless [enabled] is off in Settings.
 */
@Composable
fun rememberUpdateOffer(repo: String?, version: String?, enabled: Boolean): UpdateOffer {
    val checker = koinInject<UpdateChecker>()
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    LaunchedEffect(repo, version, enabled) {
        update = if (enabled && !repo.isNullOrBlank() && version != null) checker.check(repo, version) else null
    }
    return UpdateOffer(update) {
        val dismissed = update ?: return@UpdateOffer
        update = null
        scope.launch { checker.dismiss(dismissed.version) }
    }
}

/** A card offering the new release: download the APK (or open the release page), or not now. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val offer = LocalUpdateOffer.current
    val update = offer.update ?: return
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val tint = Theme[colors][accent]
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, tint.copy(alpha = 0.4f), shape)
            .background(tint.copy(alpha = 0.08f), shape)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Version ${update.version} is out", style = Theme[typography][bodySmall], color = Theme[colors][text])
                Text(
                    "Install it over this one; your sign-in and settings stay.",
                    style = Theme[typography][caption],
                    color = Theme[colors][textSecondary],
                )
            }
            UnstyledButton(onClick = offer.onDismiss, modifier = Modifier.size(MinTouchTarget).clip(RoundedCornerShape(Theme[radii][radiusSmall]))) {
                UnstyledIcon(Lucide.X, contentDescription = "Not now", tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            }
        }
        Button(
            if (update.apkUrl != null) "Download" else "Open release",
            onClick = { uriHandler.openUri(update.apkUrl ?: update.pageUrl) },
            size = ButtonSize.Small,
            leadingIcon = Lucide.Download,
        )
    }
}

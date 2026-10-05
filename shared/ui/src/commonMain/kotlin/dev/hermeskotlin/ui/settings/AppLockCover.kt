package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.ui.components.EmptyState

/** What the app shows instead of its screens while App lock is locked, until [onUnlock] succeeds. */
@Composable
fun AppLockCover(onUnlock: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        EmptyState(Lucide.Lock, "Herald is locked", "Unlock with your fingerprint, face or screen lock.") {
            Button("Unlock", onClick = onUnlock)
        }
    }
}

package dev.hermeskotlin.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.ArchiveRestore
import com.composables.icons.lucide.Lucide
import dev.hermeskotlin.designsystem.components.Toast
import kotlinx.coroutines.delay
import kotlin.time.Clock
import org.koin.compose.viewmodel.koinViewModel

/**
 * "Archived “title” · Undo" after a chat is archived or unarchived, from the sidebar or the chat's menu. It
 * spans the bottom of the screen, clear of the content above, and goes after [UNDO_MILLIS], or the time the user's
 * accessibility settings ask for; with a screen reader on, it stays until it's closed.
 */
@Composable
fun ArchiveUndoToast(viewModel: SessionsViewModel = koinViewModel()) {
    val undo = viewModel.state.collectAsStateWithLifecycle().value.undo
    // Kept while the toast fades out, so its text doesn't vanish first.
    var shown by remember { mutableStateOf(undo) }
    if (undo != null) shown = undo
    val accessibility = LocalAccessibilityManager.current
    val timeout = remember(accessibility) {
        accessibility?.calculateRecommendedTimeoutMillis(UNDO_MILLIS, containsIcons = true, containsText = true, containsControls = true)
            ?: UNDO_MILLIS
    }
    LaunchedEffect(undo, timeout) {
        if (undo == null || timeout == Long.MAX_VALUE) return@LaunchedEffect
        // From when it was first offered: coming back to the screen doesn't start it over.
        val left = timeout - (Clock.System.now().toEpochMilliseconds() - undo.atMillis)
        if (left > 0) delay(left)
        viewModel.dismissUndo()
    }
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(14.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = undo != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            shown?.let {
                Toast(
                    "${it.message} “${it.session.displayTitle}”",
                    modifier = Modifier.fillMaxWidth(),
                    icon = if (it.session.archived) Lucide.ArchiveRestore else Lucide.Archive,
                    actionLabel = "Undo",
                    onAction = viewModel::undoArchive,
                    // It goes by itself, unless a screen reader keeps it until it's closed.
                    onDismiss = viewModel::dismissUndo.takeIf { timeout == Long.MAX_VALUE },
                    spokenMessage = it.spoken,
                )
            }
        }
    }
}

private const val UNDO_MILLIS = 5_000L

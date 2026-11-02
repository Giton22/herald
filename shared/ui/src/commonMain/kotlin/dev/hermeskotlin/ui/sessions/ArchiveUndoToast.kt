package dev.hermeskotlin.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
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
import dev.hermeskotlin.ui.chat.AFTER_HOVER_MILLIS
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
    // Under the pointer it stays, its Undo within reach; once the pointer leaves it has a moment more at least.
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var wasHovered by remember(undo) { mutableStateOf(false) }
    if (hovered) wasHovered = true
    LaunchedEffect(undo, timeout, hovered) {
        if (undo == null || timeout == Long.MAX_VALUE || hovered) return@LaunchedEffect
        // From when it was first offered: coming back to the screen doesn't start it over.
        val left = timeout - (Clock.System.now().toEpochMilliseconds() - undo.atMillis)
        delay(left.coerceAtLeast(if (wasHovered) AFTER_HOVER_MILLIS else 0))
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
                    modifier = Modifier.widthIn(max = MaxToastWidth).fillMaxWidth().hoverable(hover),
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

/** As wide as a phone's screen at most: across a desktop window a toast would cover the composer end to end. */
internal val MaxToastWidth = 560.dp

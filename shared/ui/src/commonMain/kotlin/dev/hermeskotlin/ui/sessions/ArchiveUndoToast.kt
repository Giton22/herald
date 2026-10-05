package dev.hermeskotlin.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermeskotlin.designsystem.components.Toast
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

/**
 * "Archived · Undo" after a chat is archived or unarchived, from the sidebar or the chat's menu. It sits at
 * the bottom of the screen, above the composer, and goes after [UNDO_MILLIS].
 */
@Composable
fun ArchiveUndoToast(viewModel: SessionsViewModel = koinViewModel()) {
    val undo = viewModel.state.collectAsStateWithLifecycle().value.undo
    // Kept while the toast fades out, so its text doesn't vanish first.
    var shown by remember { mutableStateOf(undo) }
    if (undo != null) shown = undo
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(UNDO_MILLIS)
            viewModel.dismissUndo()
        }
    }
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(start = 16.dp, end = 16.dp, bottom = 112.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = undo != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            shown?.let { Toast(it.message, actionLabel = "Undo", onAction = viewModel::undoArchive) }
        }
    }
}

private const val UNDO_MILLIS = 5_000L

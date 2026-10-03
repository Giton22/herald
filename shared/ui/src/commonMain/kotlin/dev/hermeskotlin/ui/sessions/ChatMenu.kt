package dev.hermeskotlin.ui.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.ui.chat.rememberMediaActions
import dev.hermeskotlin.ui.chat.transcriptFileName
import dev.hermeskotlin.ui.chat.transcriptMarkdown
import org.koin.compose.viewmodel.koinViewModel

/**
 * The open chat's options, after Desktop's session menu: rename, pin, export, copy the id, archive and
 * delete. Shares the sidebar's [SessionsViewModel], so the list reflects each change straight away.
 */
@Composable
internal fun ChatMenu(
    visible: Boolean,
    sessionId: String?,
    title: String,
    messages: List<ChatMessage>,
    onDismiss: () -> Unit,
    onRenamed: (String?) -> Unit,
    onDeleted: () -> Unit,
    viewModel: SessionsViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val media = rememberMediaActions()

    // The listed row knows pinned/archived; a chat not loaded in the list yet falls back to its id and title.
    val session = sessionId?.let { id ->
        state.sessions.find { it.id == id } ?: state.searchResults?.find { it.id == id } ?: SessionSummary(id, title = title)
    }
    SessionActionsSheet(
        session = session.takeIf { visible },
        onDismiss = onDismiss,
        onTogglePinned = viewModel::togglePinned,
        onRename = { renameTarget = it },
        onToggleArchived = viewModel::toggleArchived,
        onDelete = { deleteTarget = it },
        onExport = { media.share(transcriptMarkdown(title, messages).encodeToByteArray(), transcriptFileName(title, it.id)) },
        onCopyId = { clipboard.setText(AnnotatedString(it.id)) },
    )
    RenameDialog(
        renameTarget,
        onDismiss = { renameTarget = null },
        onRename = { s, newTitle ->
            viewModel.rename(s, newTitle)
            onRenamed(newTitle.trim().ifBlank { null })
        },
    )
    DeleteDialog(
        deleteTarget,
        onDismiss = { deleteTarget = null },
        onDelete = {
            viewModel.delete(it)
            onDeleted()
        },
    )
}

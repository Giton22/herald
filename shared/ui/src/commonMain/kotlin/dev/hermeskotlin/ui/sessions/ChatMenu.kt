package dev.hermeskotlin.ui.sessions

import androidx.compose.ui.geometry.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.FileCheck
import com.composables.icons.lucide.Lucide
import dev.hermeskotlin.designsystem.components.Toast
import dev.hermeskotlin.ui.isDesktop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.threads.ChatThread
import dev.hermeskotlin.core.threads.ThreadsRepository
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
    onUsage: () -> Unit,
    onProcesses: () -> Unit,
    onCheckpoints: () -> Unit,
    /** The chat's goal and loops sheet; null when the chat has no goal, loop or heartbeat. */
    onControl: (() -> Unit)? = null,
    /**
     * A bot's permanent chat: its title is what makes it the bot's, so Rename, Archive and Delete would
     * lose the bot its conversation. Those are left to Desktop, which asks first.
     */
    botChat: Boolean = false,
    /** For a bot's chat: start it over (archived, a new one begun). */
    onStartFresh: (() -> Unit)? = null,
    /** Opens the stored chat a thread was started fresh into, by id and title. */
    onOpenChat: (String, String) -> Unit = { _, _ -> },
    /** The desktop's options button, for the menu to hang from; null opens it at the pointer. */
    anchor: Rect? = null,
    viewModel: SessionsViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val threads by viewModel.threads.collectAsStateWithLifecycle()
    // A thread's chat starts fresh in place: the thread stays, its chat is filed away.
    val thread = sessionId?.takeUnless { botChat }?.let { id -> ThreadsRepository.threadFor(id, threads, state.sessions) }
    var freshThread by remember { mutableStateOf<ChatThread?>(null) }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val media = rememberMediaActions()
    val scope = rememberCoroutineScope()
    var exported by remember { mutableStateOf<Exported?>(null) }
    // A phone shares the export instead, so it has nothing to say here.
    if (isDesktop) ExportedToast(exported, onDismiss = { exported = null })

    // The listed row knows pinned/archived; a chat not loaded in the list yet falls back to its id and title.
    val session = sessionId?.let { id ->
        state.sessions.find { it.id == id } ?: state.searchResults?.find { it.id == id } ?: SessionSummary(id, title = title)
    }
    SessionActionsSheet(
        session = session.takeIf { visible },
        anchor = anchor,
        onDismiss = onDismiss,
        onTogglePinned = viewModel::togglePinned.takeUnless { botChat },
        onRename = { s: SessionSummary -> renameTarget = s }.takeUnless { botChat },
        onToggleArchived = viewModel::toggleArchived.takeUnless { botChat },
        onDelete = { s: SessionSummary -> deleteTarget = s }.takeUnless { botChat },
        onExport = {
            val bytes = transcriptMarkdown(title, messages).encodeToByteArray()
            val name = transcriptFileName(title, it.id)
            // A desktop exports to a file it picks, as any desktop app's Export does; a phone hands it to the share sheet.
            if (isDesktop) scope.launch { exported = media.save(bytes, name).takeUnless { r -> r == NOT_SAVED }?.let(::Exported) } else media.share(bytes, name)
        },
        onCopyId = { clipboard.setText(AnnotatedString(it.id)) },
        onUsage = { onUsage() },
        onProcesses = { onProcesses() },
        onCheckpoints = { onCheckpoints() },
        onControl = onControl?.let { open -> { _: SessionSummary -> open() } },
        onStartFresh = onStartFresh?.let { start -> { _: SessionSummary -> start() } }
            ?: thread?.let { t -> { _: SessionSummary -> freshThread = t } },
    )
    StartThreadFreshDialog(freshThread, onDismiss = { freshThread = null }) { t ->
        viewModel.startFresh(t) { fresh -> onOpenChat(fresh.sessionId, fresh.title) }
    }
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

/** Where the export went ("Saved to …"), or why it didn't, along the bottom like the archive toast; it goes by itself. */
@Composable
private fun ExportedToast(exported: Exported?, onDismiss: () -> Unit) {
    val message = exported?.text
    // Kept while the toast fades out, so its text doesn't vanish first.
    var shown by remember { mutableStateOf(message) }
    if (message != null) shown = message
    // Keyed on the export, not its text: saving to the same file again shows the toast its full time again.
    LaunchedEffect(exported) {
        if (message == null) return@LaunchedEffect
        delay(EXPORTED_MILLIS)
        onDismiss()
    }
    Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            shown?.let { text ->
                val failed = !text.startsWith("Saved")
                Toast(text, modifier = Modifier.widthIn(max = MaxToastWidth).fillMaxWidth(),icon = if (failed) Lucide.CircleAlert else Lucide.FileCheck, onDismiss = onDismiss)
            }
        }
    }
}

/** One export's outcome; not a data class, so two exports that say the same are still two. */
private class Exported(val text: String)

/** What [dev.hermeskotlin.ui.chat.MediaActions.save] says when the Save dialog was cancelled: nothing to tell. */
private const val NOT_SAVED = "Not saved."
private const val EXPORTED_MILLIS = 5_000L

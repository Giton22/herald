package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composeunstyled.UnstyledIcon
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.well
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.Checkpoint
import dev.hermeskotlin.core.chat.CheckpointDiff
import dev.hermeskotlin.core.chat.Checkpoints
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.messageTime
import dev.hermeskotlin.ui.components.uses24HourClock
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckpointsState(
    /** Null until the first answer arrives. */
    val list: Checkpoints? = null,
    /** Offline, or a chat with nothing on the gateway yet. */
    val unavailable: Boolean = false,
    /** Why the gateway couldn't list them. */
    val error: String? = null,
    /** Each opened checkpoint's changes since; a key with a null value is still loading. */
    val diffs: Map<String, CheckpointDiff?> = emptyMap(),
    /** The checkpoint being restored right now. */
    val restoring: String? = null,
    /** How the last restore went. */
    val message: String? = null,
)

/**
 * The open chat's checkpoints: Hermes snapshots the chat's folder just before the agent changes files, and
 * any snapshot can be compared with the folder now, or restored (Desktop's and the terminal's `/rollback`).
 */
class CheckpointsController(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(CheckpointsState())
    val state: StateFlow<CheckpointsState> = _state.asStateFlow()

    private var loading: Job? = null

    /** The chat the sheet shows; answers that come back for another one, after a switch, are dropped. */
    private var shown: ChatSession? = null
    private var open = false

    /** Bumped whenever the folder or the chat may have changed, so a diff read before then is dropped. */
    private var folderVersion = 0

    fun load(chat: ChatSession?) {
        loading?.cancel()
        // Reopened on the same chat mid-restore: Restore stays off until that one ends.
        val restoring = _state.value.restoring.takeIf { chat != null && chat === shown }
        open = true
        shown = chat
        folderVersion++
        _state.value = CheckpointsState(restoring = restoring)
        loading = scope.launch {
            if (chat == null) return@launch _state.update { it.copy(unavailable = true) }
            read(chat).fold(
                onSuccess = { list -> _state.update { if (list == null) it.copy(unavailable = true) else it.copy(list = list) } },
                onFailure = { e -> _state.update { it.copy(error = e.message ?: "Couldn't read the checkpoints.") } },
            )
        }
    }

    fun close() {
        open = false
    }

    /** The open chat changed; while the sheet is up, it shows the new chat's checkpoints. */
    fun follow(chat: ChatSession?) {
        if (open && chat !== shown) load(chat)
    }

    /** Reads what changed since [checkpoint], once; a failed read is tried again on the next call. */
    fun loadDiff(chat: ChatSession?, checkpoint: Checkpoint) {
        if (chat == null || chat !== shown) return
        val known = _state.value.diffs
        if (checkpoint.hash in known && known[checkpoint.hash]?.error == null) return
        _state.update { it.copy(diffs = it.diffs + (checkpoint.hash to null)) }
        val asked = folderVersion
        scope.launch {
            val diff = chat.checkpointDiff(checkpoint)
            if (asked == folderVersion) _state.update { it.copy(diffs = it.diffs + (checkpoint.hash to diff)) }
        }
    }

    fun restore(chat: ChatSession?, checkpoint: Checkpoint) {
        if (chat == null || chat !== shown) return
        folderVersion++
        _state.update { it.copy(restoring = checkpoint.hash, message = null) }
        scope.launch {
            val outcome = chat.restoreCheckpoint(checkpoint)
            // The folder changed, so every diff read so far is stale; the gateway also snapshots it before restoring.
            val list = read(chat).getOrNull()
            if (chat === shown) {
                folderVersion++
                _state.update { it.copy(list = list ?: it.list, diffs = emptyMap(), restoring = null, message = outcome) }
            }
        }
    }

    private suspend fun read(chat: ChatSession): Result<Checkpoints?> = try {
        Result.success(chat.checkpoints())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

@Composable
fun CheckpointsSheet(
    visible: Boolean,
    controller: CheckpointsController,
    onLoad: () -> Unit,
    onDiff: (Checkpoint) -> Unit,
    onRestore: (Checkpoint) -> Unit,
    /** A reply is running; the gateway won't restore until it ends. */
    running: Boolean,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(visible) { if (visible) onLoad() else controller.close() }
    CheckpointsSheetView(visible, controller.state.collectAsStateWithLifecycle().value, onDiff, onRestore, running, onDismiss)
}

/** The checkpoints sheet's layout, apart from its controller, so previews can draw it from sample data. */
@Composable
internal fun CheckpointsSheetView(
    visible: Boolean,
    state: CheckpointsState,
    onDiff: (Checkpoint) -> Unit,
    onRestore: (Checkpoint) -> Unit,
    running: Boolean,
    onDismiss: () -> Unit,
    initiallyExpanded: String? = null,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    var confirm by remember { mutableStateOf<Checkpoint?>(null) }
    val use24Hour = uses24HourClock()
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        val list = state.list
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Checkpoints", style = Theme[typography][title], color = Theme[colors][text], modifier = Modifier.semantics { heading() })
            Text(
                when {
                    list == null || !list.enabled || list.checkpoints.isEmpty() -> "Snapshots of this chat's folder"
                    list.checkpoints.size == 1 -> "1 snapshot, taken before the agent changed files"
                    else -> "${list.checkpoints.size} snapshots, taken before the agent changed files"
                },
                style = Theme[typography][bodySmall].copy(fontSize = 13.sp),
                color = Theme[colors][textTertiary],
            )
        }
        when {
            list == null && state.error != null -> Note(state.error, error = true)
            list == null && state.unavailable ->
                Note("Available once this chat is connected and has had its first reply.")
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
            !list.enabled -> Note(
                "Checkpoints are off on the gateway. Turn them on with checkpoints.enabled in the profile's config.yaml; " +
                    "older Hermes versions read only HERMES_TUI_CHECKPOINTS=1 in the dashboard's environment.",
            )
            list.checkpoints.isEmpty() -> Note(
                "None yet. Hermes takes one just before the agent first changes a file in this chat's folder. " +
                    "It never snapshots the home folder, so start the chat in a project to get them.",
            )
            else -> LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list.checkpoints, key = { it.hash }) { checkpoint ->
                    CheckpointRow(
                        checkpoint,
                        time = checkpointTime(checkpoint, use24Hour),
                        expanded = expanded == checkpoint.hash,
                        diff = state.diffs[checkpoint.hash],
                        diffAsked = checkpoint.hash in state.diffs,
                        restoring = state.restoring == checkpoint.hash,
                        canRestore = state.restoring == null && !running,
                        onToggle = { expanded = if (expanded == checkpoint.hash) null else checkpoint.hash },
                        onDiff = { onDiff(checkpoint) },
                        onRestore = { confirm = checkpoint },
                    )
                }
            }
        }
        if (running && list?.checkpoints?.isNotEmpty() == true) Note("Restoring waits until the reply is done.")
        state.message?.let { Note(it) }
    }
    RestoreDialog(confirm, use24Hour, onDismiss = { confirm = null }, onConfirm = onRestore)
}

@Composable
private fun RestoreDialog(checkpoint: Checkpoint?, use24Hour: Boolean, onDismiss: () -> Unit, onConfirm: (Checkpoint) -> Unit) {
    // Kept while the dialog fades out, so its text doesn't lose the time.
    var shown by remember { mutableStateOf(checkpoint) }
    if (checkpoint != null) shown = checkpoint
    val target = shown ?: return
    Dialog(
        visible = checkpoint != null,
        onDismissRequest = onDismiss,
        title = "Restore this checkpoint?",
        message = "Files the agent changed go back to this snapshot (${checkpointTime(target, use24Hour)}), and " +
            "this chat's last turn is taken back. Files you edited yourself since are left alone.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Restore", onClick = { onDismiss(); onConfirm(target) }, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}

@Composable
private fun CheckpointRow(
    checkpoint: Checkpoint,
    time: String,
    expanded: Boolean,
    diff: CheckpointDiff?,
    diffAsked: Boolean,
    restoring: Boolean,
    canRestore: Boolean,
    onToggle: () -> Unit,
    onDiff: () -> Unit,
    onRestore: () -> Unit,
) {
    // Also asks again once a restore or reopening the sheet drops the changes read so far.
    LaunchedEffect(expanded, diffAsked) { if (expanded) onDiff() }
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface2])
            .border(1.dp, Theme[colors][stroke], shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide changes" else "Show changes since", onClick = onToggle)
                .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp).background(Theme[colors][surface3], CircleShape), contentAlignment = Alignment.Center) {
                UnstyledIcon(Lucide.History, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    checkpoint.message.ifBlank { "Checkpoint" },
                    style = Theme[typography][body].copy(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                    color = Theme[colors][text],
                    maxLines = if (expanded) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$time · ${checkpoint.shortHash}",
                    style = Theme[typography][code].copy(fontSize = 11.5.sp),
                    color = Theme[colors][textTertiary],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                "Restore",
                onClick = onRestore,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                loading = restoring,
                enabled = canRestore,
            )
        }
        if (expanded) {
            when {
                !diffAsked || diff == null -> Well { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Spinner() } }
                diff.error != null -> Well { Text(diff.error.orEmpty(), style = Theme[typography][bodySmall], color = Theme[colors][danger], modifier = Modifier.padding(14.dp)) }
                diff.unchanged -> Well {
                    Text(
                        "The folder is the same as at this checkpoint.",
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textSecondary],
                        modifier = Modifier.padding(14.dp),
                    )
                }
                else -> Well { DiffText(diff) }
            }
        }
    }
}

/** A recessed well under a hairline across the card's foot, as a process's output sits. */
@Composable
private fun Well(content: @Composable () -> Unit) {
    val line = Theme[colors][stroke]
    Box(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][well])
            .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
    ) { content() }
}

/** The stat lines, then the diff itself with added lines green and removed ones red; long lines scroll sideways. */
@Composable
private fun DiffText(diff: CheckpointDiff) {
    val added = Theme[colors][success]
    val removed = Theme[colors][danger]
    val plain = Theme[colors][textSecondary]
    val style = Theme[typography][code].copy(fontSize = 11.5.sp, lineHeight = 18.sp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (diff.stat.isNotBlank()) Text(diff.stat.trimEnd(), style = style, color = Theme[colors][text])
        diff.diff.trimEnd().lines().take(DIFF_LINES).forEach { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> plain
                line.startsWith("+") -> added
                line.startsWith("-") -> removed
                else -> plain
            }
            Text(line, style = style, color = color)
        }
    }
}

/** When [checkpoint] was taken, in the phone's time zone and as a message's time reads; its hash without a time. */
internal fun checkpointTime(checkpoint: Checkpoint, use24Hour: Boolean, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String =
    messageTime(checkpoint.epochSeconds, use24Hour, nowMillis).ifEmpty { checkpoint.timestamp.ifBlank { checkpoint.shortHash } }

/** The gateway sends at most 4000 characters of diff; this many lines keeps the sheet light on a phone. */
private const val DIFF_LINES = 400

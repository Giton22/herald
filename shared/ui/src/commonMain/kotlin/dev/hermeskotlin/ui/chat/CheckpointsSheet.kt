package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
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

    fun load(chat: ChatSession?) {
        loading?.cancel()
        _state.value = CheckpointsState()
        loading = scope.launch {
            val list = chat?.checkpoints()
            _state.update { if (list == null) it.copy(unavailable = true) else it.copy(list = list) }
        }
    }

    /** Reads what changed since [checkpoint], once; a failed read is tried again on the next call. */
    fun loadDiff(chat: ChatSession?, checkpoint: Checkpoint) {
        chat ?: return
        val known = _state.value.diffs
        if (checkpoint.hash in known && known[checkpoint.hash]?.error == null) return
        _state.update { it.copy(diffs = it.diffs + (checkpoint.hash to null)) }
        scope.launch {
            val diff = chat.checkpointDiff(checkpoint)
            _state.update { it.copy(diffs = it.diffs + (checkpoint.hash to diff)) }
        }
    }

    fun restore(chat: ChatSession?, checkpoint: Checkpoint) {
        chat ?: return
        _state.update { it.copy(restoring = checkpoint.hash, message = null) }
        scope.launch {
            val outcome = chat.restoreCheckpoint(checkpoint)
            // The folder changed, so every diff read so far is stale; a restore also adds no checkpoint of its own.
            val list = chat.checkpoints()
            _state.update { it.copy(list = list ?: it.list, diffs = emptyMap(), restoring = null, message = outcome) }
        }
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
    LaunchedEffect(visible) { if (visible) onLoad() }
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
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        val list = state.list
        SheetHeader(
            "Checkpoints",
            when {
                list == null || !list.enabled -> "Snapshots of this chat's folder"
                list.checkpoints.size == 1 -> "1 snapshot, taken before the agent changed files"
                else -> "${list.checkpoints.size} snapshots, taken before the agent changed files"
            },
        )
        when {
            list == null && state.unavailable ->
                Note("Available once this chat is connected and has had its first reply.")
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
            !list.enabled ->
                Note("Checkpoints are off for this profile. Turn them on with checkpoints.enabled in its config.yaml.")
            list.checkpoints.isEmpty() ->
                Note("None yet. Hermes takes one just before the agent first writes or edits a file in this chat's folder.")
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(list.checkpoints, key = { it.hash }) { checkpoint ->
                    CheckpointRow(
                        checkpoint,
                        expanded = expanded == checkpoint.hash,
                        diff = state.diffs[checkpoint.hash],
                        diffAsked = checkpoint.hash in state.diffs,
                        restoring = state.restoring == checkpoint.hash,
                        canRestore = state.restoring == null && !running,
                        onToggle = {
                            expanded = if (expanded == checkpoint.hash) null else checkpoint.hash.also { onDiff(checkpoint) }
                        },
                        onRestore = { confirm = checkpoint },
                    )
                }
            }
        }
        if (running && list?.checkpoints?.isNotEmpty() == true) Note("Restoring waits until the reply is done.")
        state.message?.let { Note(it) }
    }
    val target = confirm
    Dialog(
        visible = target != null,
        onDismissRequest = { confirm = null },
        title = "Restore this checkpoint?",
        message = "Files the agent changed go back to how they were at ${target?.let(::checkpointTime).orEmpty()}, and " +
            "this chat's last turn is taken back. Files you edited yourself since are left alone.",
        actions = {
            Button("Cancel", onClick = { confirm = null }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                "Restore",
                onClick = {
                    target?.let(onRestore)
                    confirm = null
                },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        },
    )
}

@Composable
private fun CheckpointRow(
    checkpoint: Checkpoint,
    expanded: Boolean,
    diff: CheckpointDiff?,
    diffAsked: Boolean,
    restoring: Boolean,
    canRestore: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    checkpoint.message.ifBlank { "Checkpoint" },
                    style = Theme[typography][body],
                    color = Theme[colors][text],
                    maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${checkpointTime(checkpoint)} · ${checkpoint.shortHash}",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textSecondary],
                    maxLines = 1,
                )
            }
            Button(
                "Restore",
                onClick = onRestore,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Small,
                loading = restoring,
                enabled = canRestore,
                pill = true,
            )
        }
        if (expanded) {
            when {
                !diffAsked || diff == null -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Spinner() }
                diff.error != null -> Text(diff.error.orEmpty(), style = Theme[typography][bodySmall], color = Theme[colors][danger])
                diff.unchanged -> Text(
                    "The folder is the same as at this checkpoint.",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textSecondary],
                )
                else -> DiffText(diff, shape)
            }
        }
    }
}

/** The stat lines, then the diff itself with added lines green and removed ones red; long lines scroll sideways. */
@Composable
private fun DiffText(diff: CheckpointDiff, shape: RoundedCornerShape) {
    val added = Theme[colors][success]
    val removed = Theme[colors][danger]
    val plain = Theme[colors][textSecondary]
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(shape)
            .background(Theme[colors][stroke])
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        if (diff.stat.isNotBlank()) Text(diff.stat.trimEnd(), style = Theme[typography][code], color = Theme[colors][text])
        diff.diff.trimEnd().lines().take(DIFF_LINES).forEach { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> plain
                line.startsWith("+") -> added
                line.startsWith("-") -> removed
                else -> plain
            }
            Text(line, style = Theme[typography][code], color = color)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = Theme[typography][body],
        color = Theme[colors][textSecondary],
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/** "2026-10-08 20:41" from git's ISO 8601 time, kept in the gateway's own time zone. */
internal fun checkpointTime(checkpoint: Checkpoint): String {
    val t = checkpoint.timestamp
    return if (t.length >= 16 && t[10] == 'T') "${t.substring(0, 10)} ${t.substring(11, 16)}" else t.ifBlank { checkpoint.shortHash }
}

/** The gateway sends at most 4000 characters of diff; this many lines keeps the sheet light on a phone. */
private const val DIFF_LINES = 400

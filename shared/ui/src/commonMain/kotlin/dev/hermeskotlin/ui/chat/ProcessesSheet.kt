package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import dev.hermeskotlin.core.chat.BackgroundProcess
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ProcessesState(
    /** Null until the first answer arrives. */
    val processes: List<BackgroundProcess>? = null,
    val unavailable: Boolean = false,
    /** The processes being stopped right now. */
    val stopping: Set<String> = emptySet(),
    val message: String? = null,
)

/**
 * The open chat's background processes, polled while the sheet is open, as Desktop's status stack
 * does; `process.list` is cheap and the output tail moves faster than any change event.
 */
class ProcessesController(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(ProcessesState())
    val state: StateFlow<ProcessesState> = _state.asStateFlow()

    private var polling: Job? = null

    fun start(chat: ChatSession?) {
        polling?.cancel()
        _state.value = ProcessesState()
        polling = scope.launch {
            while (isActive) {
                val processes = chat?.processes()
                _state.update {
                    if (processes == null) it.copy(unavailable = it.processes == null) else it.copy(processes = processes, unavailable = false)
                }
                delay(POLL_MILLIS)
            }
        }
    }

    fun stop() {
        polling?.cancel()
        polling = null
    }

    fun kill(chat: ChatSession?, process: BackgroundProcess) {
        chat ?: return
        _state.update { it.copy(stopping = it.stopping + process.id, message = null) }
        scope.launch {
            val outcome = chat.killProcess(process.id)
            val processes = chat.processes()
            _state.update {
                it.copy(
                    processes = processes ?: it.processes,
                    stopping = it.stopping - process.id,
                    message = outcome,
                )
            }
        }
    }

    private companion object {
        const val POLL_MILLIS = 2_000L
    }
}

@Composable
fun ProcessesSheet(
    visible: Boolean,
    controller: ProcessesController,
    onStart: () -> Unit,
    onKill: (BackgroundProcess) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(visible) { if (visible) onStart() else controller.stop() }
    ProcessesSheetView(visible, controller.state.collectAsStateWithLifecycle().value, onKill, onDismiss)
}

/** The processes sheet's layout, apart from its controller, so previews can draw it from sample data. */
@Composable
internal fun ProcessesSheetView(
    visible: Boolean,
    state: ProcessesState,
    onKill: (BackgroundProcess) -> Unit,
    onDismiss: () -> Unit,
    initiallyExpanded: String? = null,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        val processes = state.processes
        val running = processes?.count { it.running } ?: 0
        SheetHeader(
            "Background processes",
            when {
                processes == null -> "Started by the agent in this chat"
                running == 0 -> "None running"
                running == 1 -> "1 running"
                else -> "$running running"
            },
        )
        when {
            processes == null && state.unavailable ->
                Note("Available once this chat is connected and has had its first reply.")
            processes == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
            processes.isEmpty() ->
                Note("Nothing running. Dev servers, builds and watchers the agent starts in the background show up here.")
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(processes, key = { it.id }) { process ->
                    ProcessRow(
                        process,
                        expanded = expanded == process.id,
                        stopping = process.id in state.stopping,
                        onToggle = { expanded = if (expanded == process.id) null else process.id },
                        onKill = { onKill(process) },
                    )
                }
            }
        }
        state.message?.let { Note(it) }
    }
}

@Composable
private fun ProcessRow(process: BackgroundProcess, expanded: Boolean, stopping: Boolean, onToggle: () -> Unit, onKill: () -> Unit) {
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
            Box(
                Modifier.size(8.dp).background(
                    when {
                        process.running -> Theme[colors][success]
                        process.stopped -> Theme[colors][textTertiary]
                        process.exitCode != null && process.exitCode != 0 -> Theme[colors][danger]
                        else -> Theme[colors][textTertiary]
                    },
                    CircleShape,
                ),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    process.command.ifBlank { process.id },
                    style = Theme[typography][code],
                    color = Theme[colors][text],
                    maxLines = if (expanded) 6 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(process.statusLine(), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 1)
            }
            if (process.running) {
                Button("Stop", onClick = onKill, variant = ButtonVariant.Outline, size = ButtonSize.Small, loading = stopping, pill = true)
            }
        }
        if (expanded) {
            // Reverse scrolling starts at the bottom and stays there as lines arrive, like a terminal.
            Text(
                process.output.trimEnd().ifEmpty { "No output yet." }.lines().takeLast(OUTPUT_LINES).joinToString("\n"),
                style = Theme[typography][code],
                color = Theme[colors][textSecondary],
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(shape)
                    .background(Theme[colors][stroke])
                    .verticalScroll(rememberScrollState(), reverseScrolling = true)
                    .padding(10.dp),
            )
        }
    }
}

/** A line of text across a chat sheet, for its empty, off and error states. */
@Composable
internal fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = Theme[typography][body],
        color = if (error) Theme[colors][danger] else Theme[colors][textSecondary],
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

private fun BackgroundProcess.statusLine(): String = listOfNotNull(
    when {
        running -> uptimeSeconds?.let { "running for ${duration(it)}" } ?: "running"
        stopped -> "stopped"
        exitCode != null -> "exited with code $exitCode"
        else -> "finished"
    },
    pid?.let { "pid $it" },
    cwd?.takeIf { it.isNotBlank() },
).joinToString(" · ")

/** "45s", "12m", "3h 5m". */
internal fun duration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private const val OUTPUT_LINES = 40

package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.core.chat.BackgroundProcess
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.successSoft
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.well
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
        val running = processes?.filter { it.running }.orEmpty()
        val finished = processes?.filterNot { it.running }.orEmpty()
        Header(
            when {
                processes == null -> "Started by the agent in this chat"
                running.isEmpty() -> "None running"
                else -> "${running.size} running"
            },
            live = running.isNotEmpty(),
        )
        when {
            processes == null && state.unavailable ->
                Note("Available once this chat is connected and has had its first reply.")
            processes == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
            processes.isEmpty() ->
                Note("Nothing running. Dev servers, builds and watchers the agent starts in the background show up here.")
            else -> LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(running, key = { it.id }) { process ->
                    RunningCard(
                        process,
                        expanded = expanded == process.id,
                        stopping = process.id in state.stopping,
                        onToggle = { expanded = if (expanded == process.id) null else process.id },
                        onKill = { onKill(process) },
                    )
                }
                if (finished.isNotEmpty()) {
                    item(key = "finished") {
                        Text(
                            "FINISHED",
                            style = Theme[typography][eyebrow],
                            color = Theme[colors][textTertiary],
                            modifier = Modifier
                                .padding(start = 4.dp, top = 4.dp)
                                .semantics {
                                    heading()
                                    contentDescription = "Finished"
                                },
                        )
                    }
                    items(finished, key = { it.id }) { process ->
                        FinishedRow(
                            process,
                            expanded = expanded == process.id,
                            onToggle = { expanded = if (expanded == process.id) null else process.id },
                        )
                    }
                }
            }
        }
        state.message?.let { Note(it) }
    }
}

/** The sheet's title, and how many are running beside a live dot. */
@Composable
private fun Header(status: String, live: Boolean) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Background processes", style = Theme[typography][title], color = Theme[colors][text], modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(Theme[colors][successSoft], CircleShape)
                        .padding(3.dp)
                        .background(Theme[colors][success], CircleShape),
                )
            }
            Text(status, style = Theme[typography][bodySmall].copy(fontSize = 13.sp), color = Theme[colors][textTertiary])
        }
    }
}

/**
 * A running process on a ringed card: its command in mono, the program in the keyword color, how long and
 * where, and a red Stop. A tap opens its output underneath, in a recessed well.
 */
@Composable
private fun RunningCard(process: BackgroundProcess, expanded: Boolean, stopping: Boolean, onToggle: () -> Unit, onKill: () -> Unit) {
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
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide output" else "Show output", onClick = onToggle)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Command(process, maxLines = if (expanded) 6 else 2, color = Theme[colors][text])
                Text(process.statusLine(), style = Theme[typography][caption].copy(fontSize = 12.sp), color = Theme[colors][textTertiary], maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StopButton(stopping, onKill)
        }
        if (expanded) Output(process)
    }
}

/** Stop, a red pill in a full-height touch target; a spinner while it stops. */
@Composable
private fun StopButton(stopping: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val tint = Theme[colors][danger]
    // Stays clickable while stopping so a tap on "Stopping" is swallowed here instead of toggling the card;
    // it does nothing then, and says so as disabled.
    Box(
        Modifier
            .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .semantics { if (stopping) disabled() }
            .clickable(interaction, indication = null, role = Role.Button) { if (!stopping) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(CircleShape)
                .background(Theme[colors][dangerSoft])
                .indication(interaction, rememberColoredIndication(tint))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stopping) Spinner(Modifier.size(10.dp), color = tint)
            else UnstyledIcon(Lucide.Square, contentDescription = null, tint = tint, modifier = Modifier.size(10.dp))
            Text(
                if (stopping) "Stopping" else "Stop",
                style = Theme[typography][label].copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 88.dp),
            )
        }
    }
}

/**
 * A process that ended: a disc saying how (a check, a cross for an error, a square when stopped), its
 * command and exit, and a chevron that opens its output.
 */
@Composable
private fun FinishedRow(process: BackgroundProcess, expanded: Boolean, onToggle: () -> Unit) {
    val failed = !process.stopped && process.exitCode != null && process.exitCode != 0
    // No exit code and not stopped: how it ended isn't known, so the disc claims neither success nor failure.
    val unknown = !process.stopped && process.exitCode == null
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide output" else "Show output", onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(26.dp).background(if (failed) Theme[colors][dangerSoft] else Theme[colors][surface2], CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (unknown) {
                    Box(Modifier.size(6.dp).background(Theme[colors][textMuted], CircleShape))
                } else {
                    UnstyledIcon(
                        when {
                            process.stopped -> Lucide.Square
                            failed -> Lucide.X
                            else -> Lucide.Check
                        },
                        contentDescription = null,
                        tint = if (failed) Theme[colors][danger] else Theme[colors][textTertiary],
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Command(process, maxLines = if (expanded) 6 else 1, color = Theme[colors][textSecondary])
                Text(process.statusLine(), style = Theme[typography][caption].copy(fontSize = 12.sp), color = Theme[colors][textMuted], maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            UnstyledIcon(
                if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = null,
                tint = Theme[colors][textMuted],
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(Theme[radii][radiusMedium])).border(1.dp, Theme[colors][stroke], RoundedCornerShape(Theme[radii][radiusMedium]))) {
                Output(process)
            }
        }
    }
}

/** The command in mono, its program (past any `NAME=value` prefixes) in the accent text color. */
@Composable
private fun Command(process: BackgroundProcess, maxLines: Int, color: Color) {
    val accent = Theme[colors][accentText]
    val command = process.command.trim()
    val program = PROGRAM.find(command)?.groups?.get(2)
    Text(
        buildAnnotatedString {
            if (command.isEmpty()) {
                append(process.id)
            } else if (program == null) {
                append(command)
            } else {
                append(command.substring(0, program.range.first))
                withStyle(SpanStyle(color = accent)) { append(program.value) }
                append(command.substring(program.range.last + 1))
            }
        },
        style = Theme[typography][code].copy(fontSize = 12.5.sp, lineHeight = 19.sp),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The last of the output, in a recessed well under a hairline, the newest line brightest. Reverse scrolling
 * starts at the bottom and stays there as lines arrive, like a terminal.
 */
@Composable
private fun Output(process: BackgroundProcess) {
    val lines = process.output.trimEnd().lines().takeLast(OUTPUT_LINES).takeUnless { it.singleOrNull()?.isEmpty() == true }
    val line = Theme[colors][stroke]
    val dim = Theme[colors][textSecondary]
    val bright = Theme[colors][text]
    Text(
        if (lines == null) {
            AnnotatedString("No output yet.")
        } else {
            buildAnnotatedString {
                lines.forEachIndexed { index, text ->
                    if (index > 0) append("\n")
                    withStyle(SpanStyle(color = if (index == lines.lastIndex) bright else dim)) { append(text) }
                }
            }
        },
        style = Theme[typography][code].copy(fontSize = 11.5.sp, lineHeight = 18.sp),
        color = dim,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(Theme[colors][well])
            .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .verticalScroll(rememberScrollState(), reverseScrolling = true)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
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

/** "12m · pid 48211 · /opt/backup" while it runs; "Exited with code 0 · pid 47990" once it ends. */
private fun BackgroundProcess.statusLine(): String = listOfNotNull(
    when {
        running -> uptimeSeconds?.let { duration(it) } ?: "Running"
        stopped -> "Stopped"
        exitCode != null -> "Exited with code $exitCode"
        else -> "Ended"
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

/** Leading `NAME=value` assignments (group 1), then the program word (group 2). */
private val PROGRAM = Regex("""^((?:[A-Za-z_][A-Za-z0-9_]*=\S*\s+)*)(\S+)""")

package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Target
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ControlAction
import dev.hermeskotlin.core.chat.GoalControl
import dev.hermeskotlin.core.chat.GoalGate
import dev.hermeskotlin.core.chat.GoalPhase
import dev.hermeskotlin.core.chat.HeartbeatControl
import dev.hermeskotlin.core.chat.LoopControl
import dev.hermeskotlin.core.chat.SessionControl
import dev.hermeskotlin.core.chat.WaitBarrier
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.ui.components.messageTime
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.components.uses24HourClock
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An action in flight, and the last one's failure, shown in the sheet until the next action clears it. */
data class ControlPanelState(
    val busy: ControlAction? = null,
    val error: String? = null,
)

/**
 * Runs `/goal`-panel actions on the open chat. The snapshot the gateway answers with lands in the chat's
 * state on its own (ChatSession.runControl); this only tracks what's busy and what failed.
 */
class SessionControlController(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(ControlPanelState())
    val state: StateFlow<ControlPanelState> = _state.asStateFlow()

    fun run(chat: ChatSession?, action: ControlAction, text: String? = null, index: Int? = null) {
        chat ?: return
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = action, error = null) }
        scope.launch {
            val error = chat.runControl(action, text, index)
            _state.update { it.copy(busy = null, error = error) }
        }
    }
}

/**
 * The chat's automation (goal, loop, heartbeat) above the composer: one line per item present, whether
 * or not a turn is running. Tapping it opens [SessionControlSheet].
 */
@Composable
fun SessionControlStrip(control: SessionControl?, hazeState: HazeState, onOpen: () -> Unit) {
    val visible = control != null
    // Kept through the exit animation, so the panel doesn't empty before it leaves.
    var shown by remember { mutableStateOf(control) }
    if (visible) shown = control
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        shown?.let { Strip(it, hazeState, onOpen) }
    }
}

@Composable
private fun Strip(control: SessionControl, hazeState: HazeState, onOpen: () -> Unit) {
    val use24Hour = uses24HourClock()
    val lines = listOfNotNull(
        control.goal?.let { goalLine(it) to it.title },
        control.loop?.let { loopLine(it, use24Hour) to it.prompt },
        control.heartbeat?.let { heartbeatLine(it) to it.prompt },
    )
    val spoken = lines.joinToString(". ") { (line, title) -> if (title.isBlank()) line else "$line: $title" }
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val page = Theme[colors][background]
    val frosted = remember(page) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(20.dp)
            backgroundColor(page)
        }
    }
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .fillMaxWidth()
            .clip(shape)
            .hazeBlur(input = HazeInput.Sources(hazeState), style = frosted)
            .background(Theme[colors][surfaceElevated].copy(alpha = 0.85f))
            .border(1.dp, Theme[colors][strokeStrong], shape)
            .clickable(role = Role.Button, onClickLabel = "Open goal and loops", onClick = onOpen)
            .semantics { contentDescription = "$spoken. Open goal and loops" },
    ) {
        control.goal?.let { goal ->
            StripRow(
                icon = Lucide.Target,
                tint = phaseTint(goal.phase),
                line = goalLine(goal),
                title = goal.title,
            )
        }
        control.loop?.let { loop ->
            StripRow(
                icon = Lucide.Repeat,
                tint = if (loop.status == "active" && !loop.deferredByGoal) Theme[colors][accentText] else Theme[colors][textSecondary],
                line = loopLine(loop, use24Hour),
                title = loop.prompt,
            )
        }
        control.heartbeat?.let { heartbeat ->
            StripRow(
                icon = Lucide.HeartPulse,
                tint = if (heartbeat.status == "paused") Theme[colors][textSecondary] else Theme[colors][accentText],
                line = heartbeatLine(heartbeat),
                title = heartbeat.prompt,
            )
        }
    }
}

/** One of the strip's lines: the item's icon, its state summary, and what it's about, ellipsized. */
@Composable
private fun StripRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, line: String, title: String) {
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {}.padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(line, style = Theme[typography][label], color = Theme[colors][textSecondary], maxLines = 1)
        if (title.isNotBlank()) {
            Text(
                title,
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "Goal · Working · 4 of 20 turns". */
internal fun goalLine(goal: GoalControl): String =
    listOf("Goal", goalPhaseLabel(goal.phase), turnsLabel(goal.turnsUsed, goal.maxTurns))
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

/** "Loop · Running · 2 of 6 runs · next 3:45 PM". */
internal fun loopLine(loop: LoopControl, use24Hour: Boolean, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
    val next = if (loop.status == "active" && !loop.deferredByGoal && loop.nextDueAt > 0) {
        "next ${messageTime(loop.nextDueAt, use24Hour, nowMillis)}"
    } else {
        ""
    }
    return listOf("Loop", loopStateLabel(loop), runsLabel(loop.ticksFired, loop.times), next)
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
}

/** "Heartbeat · every 1 h · 3 fired"; "Heartbeat · Paused". */
internal fun heartbeatLine(heartbeat: HeartbeatControl): String =
    if (heartbeat.status == "paused") {
        "Heartbeat · Paused"
    } else {
        listOf("Heartbeat", everyLabel(heartbeat.intervalSeconds.toDouble()), firedLabel(heartbeat.fireCount))
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    }

/** How a goal reads at a glance. */
internal fun goalPhaseLabel(phase: GoalPhase): String = when (phase) {
    GoalPhase.Active -> "Working"
    GoalPhase.Paused -> "Paused"
    GoalPhase.Waiting -> "Waiting"
    GoalPhase.Blocked -> "Blocked"
    GoalPhase.Done -> "Done"
}

/** "4 of 20 turns" with a cap, "turn 4" without; nothing before the first turn. */
internal fun turnsLabel(turnsUsed: Int, maxTurns: Int): String = when {
    turnsUsed <= 0 -> ""
    maxTurns > 0 -> "$turnsUsed of $maxTurns turns"
    else -> "turn $turnsUsed"
}

/** What a loop is doing, held back by the goal first since that's why it's quiet. */
internal fun loopStateLabel(loop: LoopControl): String = when {
    loop.deferredByGoal && loop.status == "active" -> "Held by the goal"
    loop.status == "paused" -> "Paused"
    loop.status == "done" -> "Finished"
    else -> "Running"
}

/** "2 of 6 runs" with a cap, "2 runs" without; nothing before the first run. */
internal fun runsLabel(ticksFired: Int, times: Int): String = when {
    ticksFired <= 0 -> ""
    times > 0 -> "$ticksFired of $times runs"
    else -> "$ticksFired runs"
}

/** "3 fired"; "" when it never has. */
internal fun firedLabel(fireCount: Int): String = if (fireCount <= 0) "" else "$fireCount fired"

/** "every 45 s", "every 30 min", "every 1 h", "every 1 h 30 min". */
internal fun everyLabel(intervalSeconds: Double): String {
    val s = intervalSeconds.toLong().coerceAtLeast(0)
    return when {
        s < 60 -> "every $s s"
        s < 3600 -> "every ${s / 60} min"
        s % 3600 == 0L -> "every ${s / 3600} h"
        else -> "every ${s / 3600} h ${(s % 3600) / 60} min"
    }
}

/** "2 of 3 attempts · exit 1"; "not run yet" before the first try. */
internal fun gateLabel(gate: GoalGate): String {
    if (gate.attempts <= 0) return "not run yet"
    val exit = gate.lastExitCode?.let { " · exit $it" }.orEmpty()
    return "${gate.attempts} of ${gate.maxRetries + 1} attempts$exit"
}

/** "Waiting until 3:45 PM", "Waiting on another chat", "Waiting on process 4242". */
internal fun waitBarrierLabel(barrier: WaitBarrier, use24Hour: Boolean, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String =
    when (barrier) {
        is WaitBarrier.Until -> "Waiting until ${messageTime(barrier.untilAt, use24Hour, nowMillis)}"
        is WaitBarrier.OnSession -> "Waiting on another chat"
        is WaitBarrier.OnProcess -> "Waiting on process ${barrier.pid}"
    }

@Composable
private fun phaseTint(phase: GoalPhase): Color = Theme[colors][when (phase) {
    GoalPhase.Active -> accentText
    GoalPhase.Paused, GoalPhase.Waiting -> warning
    GoalPhase.Blocked -> danger
    GoalPhase.Done -> textMuted
}]

@Composable
fun SessionControlSheet(
    visible: Boolean,
    control: SessionControl?,
    controller: SessionControlController,
    onAction: (ControlAction, String?, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val panel = controller.state.collectAsStateWithLifecycle().value
    SessionControlSheetView(visible, control, panel.busy, panel.error, onAction, onDismiss)
}

/** The sheet's layout, apart from its controller, so previews can draw it from sample data. */
@Composable
internal fun SessionControlSheetView(
    visible: Boolean,
    control: SessionControl?,
    busy: ControlAction?,
    error: String?,
    onAction: (ControlAction, String?, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Kept while the sheet slides out, so it doesn't empty mid-animation.
    var shown by remember { mutableStateOf(control) }
    if (control != null) shown = control
    val use24Hour = uses24HourClock()
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        val current = shown ?: return@BottomSheet
        SheetHeader("Goal and loops", "Set with /goal, /loop and /heartbeat")
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            current.goal?.let { GoalSection(it, use24Hour, busy, onAction, onConfirm = { confirm = it }) }
            current.loop?.let { LoopSection(it, use24Hour, busy, onAction, onConfirm = { confirm = it }) }
            current.heartbeat?.let { HeartbeatSection(it, busy, onAction, onConfirm = { confirm = it }) }
        }
        error?.let { Note(it, error = true) }
        ConfirmControlAction(confirm, onDismiss = { confirm = null }, onAction = onAction)
    }
}

/** The sheet's confirmations, each saying what the action stops before it runs. */
private sealed interface ConfirmAction {
    val title: String
    val message: String
    val confirmLabel: String

    data object ClearGoal : ConfirmAction {
        override val title = "Clear this goal?"
        override val message = "The agent stops working toward it. The chat stays."
        override val confirmLabel = "Clear goal"
    }

    data class RemoveSubgoal(val index: Int, val text: String) : ConfirmAction {
        override val title = "Remove this sub-goal?"
        override val message = "“$text” leaves the goal's criteria. The goal itself stays."
        override val confirmLabel = "Remove"
    }

    data object ClearSubgoals : ConfirmAction {
        override val title = "Clear all sub-goals?"
        override val message = "The goal keeps going without its criteria."
        override val confirmLabel = "Clear all"
    }

    data object StopLoop : ConfirmAction {
        override val title = "Stop this loop?"
        override val message = "It won't fire again. The chat stays."
        override val confirmLabel = "Stop"
    }

    data object ClearHeartbeat : ConfirmAction {
        override val title = "Clear this heartbeat?"
        override val message = "It won't fire again. The chat stays."
        override val confirmLabel = "Clear"
    }
}

@Composable
private fun ConfirmControlAction(pending: ConfirmAction?, onDismiss: () -> Unit, onAction: (ControlAction, String?, Int?) -> Unit) {
    // Kept while the dialog fades out, so its text doesn't change under it.
    var shown by remember { mutableStateOf(pending) }
    if (pending != null) shown = pending
    val target = shown ?: return
    Dialog(
        visible = pending != null,
        onDismissRequest = onDismiss,
        title = target.title,
        message = target.message,
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                target.confirmLabel,
                onClick = {
                    onDismiss()
                    when (val it = pending) {
                        ConfirmAction.ClearGoal -> onAction(ControlAction.GoalClear, null, null)
                        is ConfirmAction.RemoveSubgoal -> onAction(ControlAction.SubgoalRemove, null, it.index)
                        ConfirmAction.ClearSubgoals -> onAction(ControlAction.SubgoalClear, null, null)
                        ConfirmAction.StopLoop -> onAction(ControlAction.LoopStop, null, null)
                        ConfirmAction.ClearHeartbeat -> onAction(ControlAction.HeartbeatClear, null, null)
                        null -> {}
                    }
                },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        },
    )
}

@Composable
private fun GoalSection(goal: GoalControl, use24Hour: Boolean, busy: ControlAction?, onAction: (ControlAction, String?, Int?) -> Unit, onConfirm: (ConfirmAction) -> Unit) {
    Section("GOAL") {
        SelectionContainer {
            Text(goal.title, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text])
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(goalPhaseLabel(goal.phase), turnsLabel(goal.turnsUsed, goal.maxTurns)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = Theme[typography][label],
                color = phaseTint(goal.phase),
            )
        }
        if (goal.maxTurns > 0) {
            val fraction = (goal.turnsUsed.toFloat() / goal.maxTurns).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Theme[colors][surface3])) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(Theme[colors][accentText]))
            }
        }
        // The one line that matters now: what it's parked on, why it paused, or the judge's last word.
        val barrier = goal.waitBarrier
        val relevant = when {
            barrier != null -> waitBarrierLabel(barrier, use24Hour) +
                barrier.reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            !goal.pausedReason.isNullOrBlank() -> goal.pausedReason
            !goal.lastReason.isNullOrBlank() -> goal.lastReason
            else -> null
        }
        relevant?.let { Detail(it) }
        if (!goal.contract.isEmpty) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ContractLine("Outcome", goal.contract.outcome)
                ContractLine("How it's checked", goal.contract.verification)
                ContractLine("Constraints", goal.contract.constraints)
                ContractLine("Boundaries", goal.contract.boundaries)
                ContractLine("Stop when", goal.contract.stopWhen)
            }
        }
        Subgoals(goal, busy, onAction, onConfirm)
        if (goal.gates.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("CHECKS", style = Theme[typography][eyebrow], color = Theme[colors][textTertiary], modifier = Modifier.semantics { heading() })
                goal.gates.forEach { gate ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            gate.command,
                            style = Theme[typography][code].copy(fontSize = 12.sp),
                            color = Theme[colors][textSecondary],
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(gateLabel(gate), style = Theme[typography][caption], color = Theme[colors][textMuted], maxLines = 1)
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // What Pause and Resume mean for a blocked goal follows its stored status, as Desktop does.
            val resumable = goal.status == "paused"
            when (goal.phase) {
                GoalPhase.Active, GoalPhase.Blocked ->
                    if (resumable) {
                        PanelButton("Resume", ControlAction.GoalResume, busy, onAction, leadingIcon = Lucide.Play)
                    } else {
                        PanelButton("Pause", ControlAction.GoalPause, busy, onAction, leadingIcon = Lucide.Pause)
                    }
                GoalPhase.Paused -> PanelButton("Resume", ControlAction.GoalResume, busy, onAction, leadingIcon = Lucide.Play)
                GoalPhase.Waiting -> {
                    PanelButton("Resume now", ControlAction.GoalUnwait, busy, onAction, leadingIcon = Lucide.Play)
                    PanelButton("Pause", ControlAction.GoalPause, busy, onAction, leadingIcon = Lucide.Pause)
                }
                GoalPhase.Done -> {}
            }
            Button(
                "Clear goal",
                onClick = { onConfirm(ConfirmAction.ClearGoal) },
                variant = ButtonVariant.Outline,
                size = ButtonSize.Small,
                enabled = busy == null,
                leadingIcon = Lucide.Trash2,
            )
        }
    }
}

/** The goal's criteria, each removable, with the inline add field (gone once the goal is done). */
@Composable
private fun Subgoals(goal: GoalControl, busy: ControlAction?, onAction: (ControlAction, String?, Int?) -> Unit, onConfirm: (ConfirmAction) -> Unit) {
    val newSubgoal = rememberTextFieldState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (goal.subgoals.isNotEmpty()) {
            Text("SUB-GOALS", style = Theme[typography][eyebrow], color = Theme[colors][textTertiary], modifier = Modifier.semantics { heading() })
            goal.subgoals.forEachIndexed { i, text ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(MinTouchTarget)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Remove “$text”", enabled = busy == null) { onConfirm(ConfirmAction.RemoveSubgoal(i + 1, text)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        UnstyledIcon(Lucide.X, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(13.dp))
                    }
                }
            }
            if (goal.subgoals.size > 1) {
                Button(
                    "Clear all",
                    onClick = { onConfirm(ConfirmAction.ClearSubgoals) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Small,
                    enabled = busy == null,
                )
            }
        }
        if (goal.phase != GoalPhase.Done) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    state = newSubgoal,
                    placeholder = "Add a sub-goal",
                    onKeyboardAction = { addSubgoal(newSubgoal, onAction) },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    "Add",
                    onClick = { addSubgoal(newSubgoal, onAction) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = busy == null && newSubgoal.text.isNotBlank(),
                    loading = busy == ControlAction.SubgoalAdd,
                )
            }
        }
    }
}

private fun addSubgoal(field: TextFieldState, onAction: (ControlAction, String?, Int?) -> Unit) {
    val text = field.text.toString().trim()
    if (text.isEmpty()) return
    field.clearText()
    onAction(ControlAction.SubgoalAdd, text, null)
}

@Composable
private fun LoopSection(loop: LoopControl, use24Hour: Boolean, busy: ControlAction?, onAction: (ControlAction, String?, Int?) -> Unit, onConfirm: (ConfirmAction) -> Unit) {
    Section("LOOP") {
        if (loop.prompt.isNotBlank()) Detail(loop.prompt)
        val cadence = if (loop.selfPaced) "Self-paced" else everyLabel(loop.intervalSeconds).replaceFirstChar { it.uppercase() }
        Text(
            listOf(loopStateLabel(loop), cadence, runsLabel(loop.ticksFired, loop.times)).filter { it.isNotEmpty() }.joinToString(" · "),
            style = Theme[typography][label],
            color = Theme[colors][textSecondary],
        )
        if (loop.status == "active" && !loop.deferredByGoal && loop.nextDueAt > 0) {
            Detail("Next run ${messageTime(loop.nextDueAt, use24Hour)}")
        }
        if (loop.until.isNotBlank()) Detail("Until: ${loop.until}")
        if (loop.deferredByGoal) Detail("Waits while the goal is working.")
        if (loop.awaitingResponse) Detail("Waiting for its last reply.")
        loop.pausedReason?.takeIf { it.isNotBlank() }?.let { Detail(it) }
        loop.lastStopReason?.takeIf { it.isNotBlank() }?.let { Detail("Stopped: $it") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (loop.status) {
                "active" -> PanelButton("Pause", ControlAction.LoopPause, busy, onAction, leadingIcon = Lucide.Pause)
                "paused" -> PanelButton("Resume", ControlAction.LoopResume, busy, onAction, leadingIcon = Lucide.Play)
            }
            if (loop.status == "done") {
                PanelButton("Dismiss", ControlAction.LoopStop, busy, onAction, leadingIcon = Lucide.X)
            } else {
                Button(
                    "Stop",
                    onClick = { onConfirm(ConfirmAction.StopLoop) },
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Small,
                    enabled = busy == null,
                    leadingIcon = Lucide.Square,
                )
            }
        }
    }
}

@Composable
private fun HeartbeatSection(heartbeat: HeartbeatControl, busy: ControlAction?, onAction: (ControlAction, String?, Int?) -> Unit, onConfirm: (ConfirmAction) -> Unit) {
    Section("HEARTBEAT") {
        if (heartbeat.prompt.isNotBlank()) Detail(heartbeat.prompt)
        Text(
            if (heartbeat.status == "paused") {
                "Paused"
            } else {
                listOf(everyLabel(heartbeat.intervalSeconds.toDouble()).replaceFirstChar { it.uppercase() }, firedLabel(heartbeat.fireCount))
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
            },
            style = Theme[typography][label],
            color = Theme[colors][textSecondary],
        )
        if (heartbeat.lastFiredAt > 0) {
            val ago = relativeTime(heartbeat.lastFiredAt)
            if (ago.isNotEmpty()) Detail("Last fired ${if (ago == "now") "just now" else "$ago ago"}")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (heartbeat.status == "paused") {
                PanelButton("Resume", ControlAction.HeartbeatResume, busy, onAction, leadingIcon = Lucide.Play)
            } else {
                PanelButton("Pause", ControlAction.HeartbeatPause, busy, onAction, leadingIcon = Lucide.Pause)
            }
            Button(
                "Clear",
                onClick = { onConfirm(ConfirmAction.ClearHeartbeat) },
                variant = ButtonVariant.Outline,
                size = ButtonSize.Small,
                enabled = busy == null,
                leadingIcon = Lucide.Trash2,
            )
        }
    }
}

/** A section of the sheet: its eyebrow header on a ringed card. */
@Composable
private fun Section(header: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface2])
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(header, style = Theme[typography][eyebrow], color = Theme[colors][textTertiary], modifier = Modifier.semantics { heading() })
        content()
    }
}

/** A quiet line of detail inside a section. */
@Composable
private fun Detail(text: String) {
    Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
}

@Composable
private fun ContractLine(name: String, value: String) {
    if (value.isBlank()) return
    Text("$name: $value", style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
}

/** A section's main action: a small secondary pill that spins while its action is in flight. */
@Composable
private fun PanelButton(
    text: String,
    action: ControlAction,
    busy: ControlAction?,
    onAction: (ControlAction, String?, Int?) -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Button(
        text,
        onClick = { onAction(action, null, null) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = busy == null,
        loading = busy == action,
        leadingIcon = leadingIcon,
    )
}

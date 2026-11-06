package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.ui.frostedPanel
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.hermeskotlin.designsystem.accent
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
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.handCursor
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
import dev.hermeskotlin.ui.CopyableOnDesktop
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
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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

    /** The chat the busy flag and the error belong to; results from another one are dropped. */
    private var followed: ChatSession? = null

    /** The open chat changed; whatever was running or failed was about the previous one. */
    fun follow(chat: ChatSession?) {
        if (chat === followed) return
        followed = chat
        _state.value = ControlPanelState()
    }

    /** Forgets the last failure: the sheet was closed on it. */
    fun clearError() = _state.update { it.copy(error = null) }

    fun run(chat: ChatSession?, action: ControlAction, text: String? = null, index: Int? = null) {
        chat ?: return
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = action, error = null) }
        scope.launch {
            val error = chat.runControl(action, text, index)
            // The user may have switched chats mid-action; that chat's result isn't ours to show.
            if (chat === followed) _state.update { it.copy(busy = null, error = error) }
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
    val rows = listOfNotNull(
        control.goal?.let { StripItem(Lucide.Target, phaseTint(it.phase), goalLine(it).line(), it.title) },
        control.loop?.let { loop ->
            val tint = if (loop.status == "active" && !loop.deferredByGoal) Theme[colors][accentText] else Theme[colors][textSecondary]
            StripItem(Lucide.Repeat, tint, loopLine(loop, use24Hour).line(), loop.prompt)
        },
        control.heartbeat?.let { heartbeat ->
            val tint = if (heartbeat.status == "paused") Theme[colors][textSecondary] else Theme[colors][accentText]
            StripItem(Lucide.HeartPulse, tint, heartbeatLine(heartbeat).line(), heartbeat.prompt)
        },
    )
    val spokenRows = rows.map { if (it.title.isBlank()) it.line else stringResource(Res.string.chat_detail_line, it.line, it.title) }
    val openLabel = stringResource(Res.string.chat_control_open)
    val spoken = stringResource(Res.string.chat_control_spoken, spokenRows.joinToString(". "), openLabel)
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
            .frostedPanel(shape, Theme[colors][surfaceElevated].copy(alpha = 0.85f), Theme[colors][strokeStrong], Modifier.hazeBlur(input = HazeInput.Sources(hazeState), style = frosted))
            .handCursor()
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
            .semantics { contentDescription = spoken }
            // The whole strip is the touch target, so its rows sit close rather than 48dp apart each.
            .heightIn(min = MinTouchTarget)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        rows.forEach { StripRow(it) }
    }
}

/** One of the strip's lines, worked out once for both the row and what the strip says aloud. */
private class StripItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val tint: Color, val line: String, val title: String)

/** One of the strip's lines: the item's icon, its state summary, and what it's about, ellipsized. */
@Composable
private fun StripRow(item: StripItem) {
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {}.padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(item.icon, contentDescription = null, tint = item.tint, modifier = Modifier.size(14.dp))
        // On a narrow phone the state can fill the row; it ends in an ellipsis then, never cut mid-word.
        Text(item.line, style = Theme[typography][label], color = Theme[colors][textSecondary], maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.title.isNotBlank()) {
            Text(
                item.title,
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A piece of a label, chosen in plain code and put into words in a composable: a catalogue string with its
 * arguments, a count, or text that already is words (the gateway's, a clock time).
 */
internal sealed interface Words {
    data class Of(val res: StringResource, val args: List<Any> = emptyList()) : Words

    data class Count(val res: PluralStringResource, val count: Int, val args: List<Any> = listOf(count)) : Words

    data class Said(val text: String) : Words
}

@Composable
internal fun Words.text(): String = when (this) {
    is Words.Of -> stringResource(res, *args.toTypedArray())
    is Words.Count -> pluralStringResource(res, count, *args.toTypedArray())
    is Words.Said -> text
}

/** The pieces as one line, "Goal · Working · 4 of 20 turns". */
@Composable
internal fun List<Words>.line(): String = map { it.text() }.joinToString(" · ")

/** "Goal · Working · 4 of 20 turns". */
internal fun goalLine(goal: GoalControl): List<Words> =
    listOfNotNull(Words.Of(Res.string.chat_goal), Words.Of(goalPhaseLabel(goal.phase)), turnsLabel(goal.turnsUsed, goal.maxTurns))

/** "Loop · Running · 2 of 6 runs · next 3:45 PM". */
internal fun loopLine(loop: LoopControl, use24Hour: Boolean, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): List<Words> {
    val next = if (loop.status == "active" && !loop.deferredByGoal && loop.nextDueAt > 0) {
        // A past due time reads as now, not as a clock time that already went by.
        if (loop.nextDueAt * 1000 <= nowMillis) {
            Words.Of(Res.string.chat_loop_due_now)
        } else {
            Words.Of(Res.string.chat_loop_next, listOf(messageTime(loop.nextDueAt, use24Hour, nowMillis)))
        }
    } else {
        null
    }
    return listOfNotNull(Words.Of(Res.string.chat_loop), Words.Of(loopStateLabel(loop)), runsLabel(loop.ticksFired, loop.times), next)
}

/** "Heartbeat · every 1 h · 3 fired"; "Heartbeat · Paused". */
internal fun heartbeatLine(heartbeat: HeartbeatControl): List<Words> =
    if (heartbeat.status == "paused") {
        listOf(Words.Of(Res.string.chat_heartbeat), Words.Of(Res.string.chat_paused))
    } else {
        listOfNotNull(Words.Of(Res.string.chat_heartbeat), everyLabel(heartbeat.intervalSeconds.toDouble()), firedLabel(heartbeat.fireCount))
    }

/** How a goal reads at a glance. */
internal fun goalPhaseLabel(phase: GoalPhase): StringResource = when (phase) {
    GoalPhase.Active -> Res.string.chat_goal_working
    GoalPhase.Paused -> Res.string.chat_paused
    GoalPhase.Waiting -> Res.string.chat_goal_waiting
    GoalPhase.Blocked -> Res.string.chat_goal_blocked
    GoalPhase.Done -> Res.string.chat_subagent_done
}

/** "4 of 20 turns" with a cap, "turn 4" without; nothing before the first turn. */
internal fun turnsLabel(turnsUsed: Int, maxTurns: Int): Words? = when {
    turnsUsed <= 0 -> null
    maxTurns > 0 -> Words.Count(Res.plurals.chat_goal_turns_of, maxTurns, listOf(turnsUsed, maxTurns))
    else -> Words.Of(Res.string.chat_goal_turn, listOf(turnsUsed))
}

/** What a loop is doing, held back by the goal first since that's why it's quiet. */
internal fun loopStateLabel(loop: LoopControl): StringResource = when {
    loop.deferredByGoal && loop.status == "active" -> Res.string.chat_loop_held
    loop.status == "paused" -> Res.string.chat_paused
    loop.status == "done" -> Res.string.chat_loop_finished
    else -> Res.string.chat_process_running
}

/** "2 of 6 runs" with a cap, "2 runs" ("1 run") without; nothing before the first run. */
internal fun runsLabel(ticksFired: Int, times: Int): Words? = when {
    ticksFired <= 0 -> null
    times > 0 -> Words.Count(Res.plurals.chat_loop_runs_of, times, listOf(ticksFired, times))
    else -> Words.Count(Res.plurals.chat_loop_runs, ticksFired)
}

/**
 * Why a goal or loop paused, as the gateway sent it. Its own reasons are sentences ("turn budget
 * exhausted (20/20)"), except a plain pause, which says `user-paused`: the Paused label says that already.
 */
internal fun pausedReasonLabel(reason: String?): String? =
    reason?.trim()?.takeIf { it.isNotEmpty() && it != USER_PAUSED }

private const val USER_PAUSED = "user-paused"

/** "3 fired"; nothing when it never has. */
internal fun firedLabel(fireCount: Int): Words? = if (fireCount <= 0) null else Words.Count(Res.plurals.chat_heartbeat_fired, fireCount)

/** "every 45 s", "every 30 min", "every 1 h", "every 1 h 30 min"; nothing for no interval at all. */
internal fun everyLabel(intervalSeconds: Double): Words? {
    val s = intervalSeconds.toLong()
    if (s <= 0) return null
    return when {
        s < 60 -> Words.Of(Res.string.chat_every_seconds, listOf(s))
        s < 3600 -> Words.Of(Res.string.chat_every_minutes, listOf(s / 60))
        s % 3600 == 0L -> Words.Of(Res.string.chat_every_hours, listOf(s / 3600))
        else -> Words.Of(Res.string.chat_every_hours_minutes, listOf(s / 3600, (s % 3600) / 60))
    }
}

/** "2 of 3 attempts · exit 1"; "not run yet" before the first try. */
internal fun gateLabel(gate: GoalGate): List<Words> {
    if (gate.attempts <= 0) return listOf(Words.Of(Res.string.chat_gate_not_run))
    val tries = gate.maxRetries + 1
    return listOfNotNull(
        Words.Count(Res.plurals.chat_gate_attempts, tries, listOf(gate.attempts, tries)),
        gate.lastExitCode?.let { Words.Of(Res.string.chat_gate_exit, listOf(it)) },
    )
}

/**
 * "Waiting until 3:45 PM", "Waiting to resume" once that time passed, "Waiting on another chat"; the barrier's
 * reason goes after it, as [waitBarrierText] puts it.
 */
internal fun waitBarrierLabel(barrier: WaitBarrier, use24Hour: Boolean, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): Words =
    when (barrier) {
        is WaitBarrier.Until ->
            // The wait's end time went by without the barrier lifting; a past clock time would mislead.
            if (barrier.untilAt * 1000 <= nowMillis) {
                Words.Of(Res.string.chat_wait_resume)
            } else {
                Words.Of(Res.string.chat_wait_until, listOf(messageTime(barrier.untilAt, use24Hour, nowMillis)))
            }
        is WaitBarrier.OnSession -> Words.Of(Res.string.chat_wait_other_chat)
        is WaitBarrier.OnProcess -> Words.Of(Res.string.chat_wait_process, listOf(barrier.pid))
    }

/** [waitBarrierLabel], then the barrier's reason when it gave one: "Waiting to resume: CI is running". */
@Composable
private fun waitBarrierText(barrier: WaitBarrier, use24Hour: Boolean): String {
    val label = waitBarrierLabel(barrier, use24Hour).text()
    return if (barrier.reason.isBlank()) label else stringResource(Res.string.chat_detail_line, label, barrier.reason)
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
    // A confirmation belongs to this showing: the sheet can close under it (the goal cleared on
    // another client), and it mustn't come back over whatever the next showing holds.
    LaunchedEffect(visible) { if (!visible) confirm = null }
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        val current = shown ?: return@BottomSheet
        SheetHeader(stringResource(Res.string.chat_control_title), stringResource(Res.string.chat_control_subtitle))
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScrollWithBar().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            current.goal?.let { GoalSection(it, use24Hour, busy, onAction, onConfirm = { confirm = it }) }
            current.loop?.let { LoopSection(it, use24Hour, busy, onAction, onConfirm = { confirm = it }) }
            current.heartbeat?.let { HeartbeatSection(it, busy, onAction, onConfirm = { confirm = it }) }
        }
        error?.let { Note(it, error = true) }
        ConfirmControlAction(confirm, current.goal?.subgoals.orEmpty(), onDismiss = { confirm = null }, onAction = onAction)
    }
}

/** The sheet's confirmations, each saying what the action stops before it runs. */
private sealed interface ConfirmAction {
    val title: StringResource
    val message: Words
    val confirmLabel: StringResource

    data object ClearGoal : ConfirmAction {
        override val title = Res.string.chat_goal_clear_title
        override val message = Words.Of(Res.string.chat_goal_clear_message)
        override val confirmLabel = Res.string.chat_goal_clear
    }

    // The index isn't stored: the list may change under the open dialog, so it's resolved at confirm time.
    data class RemoveSubgoal(val text: String) : ConfirmAction {
        override val title = Res.string.chat_subgoal_remove_title
        override val message = Words.Of(Res.string.chat_subgoal_remove_message, listOf(text))
        override val confirmLabel = Res.string.common_remove
    }

    data object ClearSubgoals : ConfirmAction {
        override val title = Res.string.chat_subgoals_clear_title
        override val message = Words.Of(Res.string.chat_subgoals_clear_message)
        override val confirmLabel = Res.string.chat_clear_all
    }

    data object StopLoop : ConfirmAction {
        override val title = Res.string.chat_loop_stop_title
        override val message = Words.Of(Res.string.chat_wont_fire_again)
        override val confirmLabel = Res.string.chat_stop
    }

    data object ClearHeartbeat : ConfirmAction {
        override val title = Res.string.chat_heartbeat_clear_title
        override val message = Words.Of(Res.string.chat_wont_fire_again)
        override val confirmLabel = Res.string.chat_clear
    }
}

/** The 1-based index of [text] in [subgoals] (first match), null when it's no longer there. */
internal fun subgoalIndexOf(subgoals: List<String>, text: String): Int? =
    subgoals.indexOf(text).takeIf { it >= 0 }?.plus(1)

@Composable
private fun ConfirmControlAction(pending: ConfirmAction?, subgoals: List<String>, onDismiss: () -> Unit, onAction: (ControlAction, String?, Int?) -> Unit) {
    // Kept while the dialog fades out, so its text doesn't change under it.
    var shown by remember { mutableStateOf(pending) }
    if (pending != null) shown = pending
    val target = shown ?: return
    Dialog(
        visible = pending != null,
        onDismissRequest = onDismiss,
        title = stringResource(target.title),
        message = target.message.text(),
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                stringResource(target.confirmLabel),
                onClick = {
                    onDismiss()
                    when (val it = pending) {
                        ConfirmAction.ClearGoal -> onAction(ControlAction.GoalClear, null, null)
                        // Resolve against the live list; if the text is gone, the dialog just closes.
                        is ConfirmAction.RemoveSubgoal -> subgoalIndexOf(subgoals, it.text)?.let { index ->
                            onAction(ControlAction.SubgoalRemove, null, index)
                        }
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
    Section(stringResource(Res.string.chat_goal)) {
        SelectionContainer {
            Text(goal.title, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text])
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(Words.Of(goalPhaseLabel(goal.phase)), turnsLabel(goal.turnsUsed, goal.maxTurns)).line(),
                style = Theme[typography][label],
                color = phaseTint(goal.phase),
            )
        }
        if (goal.maxTurns > 0) {
            val fraction = (goal.turnsUsed.toFloat() / goal.maxTurns).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Theme[colors][surface3])) {
                // Progress is one of the three things the accent fills (accentText is for text).
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(Theme[colors][accent]))
            }
        }
        // The one line that matters now: what it's parked on, why it paused, or the judge's last word.
        val barrier = goal.waitBarrier
        val pausedReason = pausedReasonLabel(goal.pausedReason)
        val relevant = when {
            barrier != null -> waitBarrierText(barrier, use24Hour)
            pausedReason != null -> pausedReason
            !goal.lastReason.isNullOrBlank() -> goal.lastReason
            else -> null
        }
        relevant?.let { Detail(it) }
        if (!goal.contract.isEmpty) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ContractLine(stringResource(Res.string.chat_contract_outcome), goal.contract.outcome)
                ContractLine(stringResource(Res.string.chat_contract_verification), goal.contract.verification, codeValue = true)
                ContractLine(stringResource(Res.string.chat_contract_constraints), goal.contract.constraints)
                ContractLine(stringResource(Res.string.chat_contract_boundaries), goal.contract.boundaries)
                ContractLine(stringResource(Res.string.chat_contract_stop_when), goal.contract.stopWhen)
            }
        }
        Subgoals(goal, busy, onAction, onConfirm)
        if (goal.gates.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.chat_goal_checks).uppercase(), style = Theme[typography][eyebrow], color = Theme[colors][textTertiary], modifier = Modifier.semantics { heading() })
                goal.gates.forEach { gate ->
                    Column {
                        Text(
                            gate.command,
                            style = Theme[typography][code].copy(fontSize = 12.sp),
                            color = Theme[colors][textSecondary],
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(gateLabel(gate).line(), style = Theme[typography][caption], color = Theme[colors][textMuted])
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
                        PanelButton(stringResource(Res.string.chat_resume), ControlAction.GoalResume, busy, onAction, leadingIcon = Lucide.Play)
                    } else {
                        PanelButton(stringResource(Res.string.chat_pause), ControlAction.GoalPause, busy, onAction, leadingIcon = Lucide.Pause)
                    }
                GoalPhase.Paused -> PanelButton(stringResource(Res.string.chat_resume), ControlAction.GoalResume, busy, onAction, leadingIcon = Lucide.Play)
                GoalPhase.Waiting -> {
                    PanelButton(stringResource(Res.string.chat_resume_now), ControlAction.GoalUnwait, busy, onAction, leadingIcon = Lucide.Play)
                    PanelButton(stringResource(Res.string.chat_pause), ControlAction.GoalPause, busy, onAction, leadingIcon = Lucide.Pause)
                }
                GoalPhase.Done -> {}
            }
            Button(
                stringResource(Res.string.chat_goal_clear),
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
    // The field keeps what the user typed until the gateway confirms it: it's cleared only once the
    // submitted text shows up in the goal's sub-goals, so a failed add loses nothing.
    var submitted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(goal.subgoals) {
        submitted?.takeIf { it in goal.subgoals }?.let {
            submitted = null
            newSubgoal.clearText()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (goal.subgoals.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.chat_subgoals).uppercase(), style = Theme[typography][eyebrow], color = Theme[colors][textTertiary], modifier = Modifier.weight(1f).semantics { heading() })
                if (goal.subgoals.size > 1) {
                    Button(
                        stringResource(Res.string.chat_clear_all),
                        onClick = { onConfirm(ConfirmAction.ClearSubgoals) },
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Small,
                        enabled = busy == null,
                    )
                }
            }
            goal.subgoals.forEach { text ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
                    // Named on hover on the desktop, as every icon button is.
                    IconButton(
                        Lucide.X,
                        contentDescription = stringResource(Res.string.chat_subgoal_remove),
                        onClick = { onConfirm(ConfirmAction.RemoveSubgoal(text)) },
                        enabled = busy == null,
                        tint = Theme[colors][textTertiary],
                        iconSize = 13.dp,
                    )
                }
            }
        }
        if (goal.phase != GoalPhase.Done) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    state = newSubgoal,
                    placeholder = stringResource(Res.string.chat_subgoal_add_placeholder),
                    onKeyboardAction = { addSubgoal(newSubgoal, busy, onAction, onSubmitted = { submitted = it }) },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    stringResource(Res.string.chat_add),
                    onClick = { addSubgoal(newSubgoal, busy, onAction, onSubmitted = { submitted = it }) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = busy == null && newSubgoal.text.isNotBlank(),
                    loading = busy == ControlAction.SubgoalAdd,
                )
            }
        }
    }
}

private fun addSubgoal(field: TextFieldState, busy: ControlAction?, onAction: (ControlAction, String?, Int?) -> Unit, onSubmitted: (String) -> Unit) {
    // The keyboard path bypasses the disabled Add button, so it has to respect busy on its own.
    if (busy != null) return
    val text = field.text.toString().trim()
    if (text.isEmpty()) return
    onSubmitted(text)
    onAction(ControlAction.SubgoalAdd, text, null)
}

@Composable
private fun LoopSection(loop: LoopControl, use24Hour: Boolean, busy: ControlAction?, onAction: (ControlAction, String?, Int?) -> Unit, onConfirm: (ConfirmAction) -> Unit) {
    Section(stringResource(Res.string.chat_loop)) {
        if (loop.prompt.isNotBlank()) Detail(loop.prompt)
        val cadence = if (loop.selfPaced) stringResource(Res.string.chat_loop_self_paced) else everyLabel(loop.intervalSeconds)?.text()?.replaceFirstChar { it.uppercase() }
        Text(
            listOfNotNull(stringResource(loopStateLabel(loop)), cadence, runsLabel(loop.ticksFired, loop.times)?.text()).joinToString(" · "),
            style = Theme[typography][label],
            color = Theme[colors][textSecondary],
        )
        if (loop.status == "active" && !loop.deferredByGoal && loop.nextDueAt > 0) {
            Detail(
                if (loop.nextDueAt * 1000 <= Clock.System.now().toEpochMilliseconds()) {
                    stringResource(Res.string.chat_loop_due_now_detail)
                } else {
                    stringResource(Res.string.chat_loop_next_run, messageTime(loop.nextDueAt, use24Hour))
                },
            )
        }
        if (loop.until.isNotBlank()) Detail(stringResource(Res.string.chat_loop_until, loop.until))
        if (loop.deferredByGoal) Detail(stringResource(Res.string.chat_loop_waits_for_goal))
        if (loop.awaitingResponse) Detail(stringResource(Res.string.chat_loop_awaiting_reply))
        pausedReasonLabel(loop.pausedReason)?.let { Detail(it) }
        loop.lastStopReason?.takeIf { it.isNotBlank() }?.let { Detail(stringResource(Res.string.chat_loop_stopped_reason, it)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (loop.status) {
                "active" -> PanelButton(stringResource(Res.string.chat_pause), ControlAction.LoopPause, busy, onAction, leadingIcon = Lucide.Pause)
                "paused" -> PanelButton(stringResource(Res.string.chat_resume), ControlAction.LoopResume, busy, onAction, leadingIcon = Lucide.Play)
            }
            if (loop.status == "done") {
                PanelButton(stringResource(Res.string.chat_dismiss), ControlAction.LoopStop, busy, onAction, leadingIcon = Lucide.X)
            } else {
                Button(
                    stringResource(Res.string.chat_stop),
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
    Section(stringResource(Res.string.chat_heartbeat)) {
        if (heartbeat.prompt.isNotBlank()) Detail(heartbeat.prompt)
        Text(
            if (heartbeat.status == "paused") {
                stringResource(Res.string.chat_paused)
            } else {
                listOfNotNull(
                    everyLabel(heartbeat.intervalSeconds.toDouble())?.text()?.replaceFirstChar { it.uppercase() },
                    firedLabel(heartbeat.fireCount)?.text(),
                ).joinToString(" · ")
            },
            style = Theme[typography][label],
            color = Theme[colors][textSecondary],
        )
        if (heartbeat.lastFiredAt > 0) {
            val ago = relativeTime(heartbeat.lastFiredAt)
            if (ago.isNotEmpty()) {
                Detail(if (ago == "now") stringResource(Res.string.chat_heartbeat_fired_now) else stringResource(Res.string.chat_heartbeat_fired_ago, ago))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (heartbeat.status == "paused") {
                PanelButton(stringResource(Res.string.chat_resume), ControlAction.HeartbeatResume, busy, onAction, leadingIcon = Lucide.Play)
            } else {
                PanelButton(stringResource(Res.string.chat_pause), ControlAction.HeartbeatPause, busy, onAction, leadingIcon = Lucide.Pause)
            }
            Button(
                stringResource(Res.string.chat_clear),
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
        Text(header.uppercase(), style = Theme[typography][eyebrow], color = Theme[colors][textTertiary], modifier = Modifier.semantics { heading() })
        content()
    }
}

/** A quiet line of detail inside a section. */
@Composable
private fun Detail(text: String) {
    // A loop's prompt or why it stopped: the desktop selects it to copy.
    CopyableOnDesktop { Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary]) }
}

@Composable
private fun ContractLine(name: String, value: String, codeValue: Boolean = false) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = Theme[typography][eyebrow], color = Theme[colors][textTertiary])
        Text(
            value,
            style = if (codeValue) Theme[typography][code] else Theme[typography][bodySmall],
            color = Theme[colors][textSecondary],
        )
    }
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

package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.composables.icons.lucide.Square
import com.composeunstyled.UnstyledButton
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.textMuted
import kotlin.time.Clock
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleSlash
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.CircleDashed
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.chat.TodoItem
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.delay

/**
 * The agent's plan above the composer. While its turn runs it's open and ticks off as the agent works;
 * afterwards it folds to a "Last plan" line to look back at. The agent alone ends it: a new plan
 * replaces it, an emptied one removes it.
 */
@Composable
fun TodoPanel(todos: TodoList?, live: Boolean, hazeState: HazeState) {
    // Once a live plan finishes, let the last check land before it folds away.
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(live) {
        if (live) {
            settling = true
        } else if (settling) {
            delay(FINISHED_LINGER_MS)
            settling = false
        }
    }
    val visible = todos != null && todos.items.isNotEmpty()
    // Kept through the exit animation, so the panel doesn't empty before it leaves.
    var shown by remember { mutableStateOf(todos) }
    if (visible) shown = todos
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        shown?.let { list ->
            Panel(list, live = live || settling, hazeState)
        }
    }
}

@Composable
private fun Panel(todos: TodoList, live: Boolean, hazeState: HazeState) {
    // Open while the agent works through it, folded once it's a past plan.
    var expanded by remember(live) { mutableStateOf(live) }
    FrostedPanel(hazeState) {
        val current = todos.items.firstOrNull { it.status == TodoStatus.InProgress }
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.ListChecks, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(16.dp))
            Text(
                "${if (live) "Tasks" else "Last plan"} ${todos.done}/${todos.total}",
                style = Theme[typography][label],
                color = if (live) Theme[colors][text] else Theme[colors][textSecondary],
            )
            Box(Modifier.weight(1f)) {
                // Folded, the step in hand still shows.
                if (!expanded && live && current != null) {
                    Text(
                        current.content,
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textSecondary],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            UnstyledIcon(
                if (expanded) Lucide.ChevronDown else Lucide.ChevronUp,
                contentDescription = if (expanded) "Fold the tasks" else "Show the tasks",
                tint = Theme[colors][textTertiary],
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                todos.tree().forEach { (item, depth) -> TodoRow(item, depth, live) }
            }
        }
    }
}

/**
 * The live task card above the composer while a turn runs: [step] beside a spinner, how long the turn has
 * run, and Stop; under it the plan's progress when there is one. The technical details stay folded
 * underneath until asked for: the gateway's status text, the tool and what it was given, and the plan
 * step by step.
 */
@Composable
internal fun ProgressPanel(
    step: LiveStep,
    status: String?,
    tool: ToolActivity?,
    todos: TodoList?,
    /** The running turn, which the running time counts from when it showed here; null hides the time. */
    turnKey: String?,
    onStop: () -> Unit,
    canStop: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val plan = todos?.takeIf { it.items.isNotEmpty() }
    val statusDetail = status?.takeIf { it.isNotBlank() && it != step.title }
    val toolDetail = tool?.let { t -> listOfNotNull(t.name, t.detail?.takeIf { it.isNotBlank() }).joinToString(": ") }
    val hasDetails = statusDetail != null || toolDetail != null || plan != null
    ShimmerCard {
        // The header opens and folds the details; the details themselves scroll without folding.
        Column(
            Modifier.then(
                if (hasDetails) {
                    Modifier
                        .clickable(onClickLabel = if (expanded) "Hide details" else "Show details") { expanded = !expanded }
                        .semantics { stateDescription = if (expanded) "Details shown" else "Details hidden" }
                } else {
                    Modifier
                },
            ),
        ) {
            CardHeader(step, turnKey, plan, hasDetails, expanded, onStop, canStop)
        }
        AnimatedVisibility(visible = expanded && hasDetails) {
            Column(
                Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                statusDetail?.let { DetailLine("Status", it) }
                toolDetail?.let { DetailLine("Tool", it) }
                plan?.let { list ->
                    Text("Tasks ${list.done}/${list.total}", style = Theme[typography][label], color = Theme[colors][textSecondary])
                    list.tree().forEach { (item, depth) -> TodoRow(item, depth, live = true) }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(name: String, value: String) {
    Text(
        "$name: $value",
        style = Theme[typography][bodySmall],
        color = Theme[colors][textSecondary],
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The live task card: an opaque surface inside a 1dp edge whose accent highlight sweeps round while the task runs. */
@Composable
private fun ShimmerCard(content: @Composable ColumnScope.() -> Unit) {
    val radius = Theme[radii][radiusLarge]
    val soft = Theme[colors][accentSoft]
    val bright = Theme[colors][accentText]
    val sweep = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)),
        label = "sweep",
    )
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .fillMaxWidth()
            // Read in the draw phase only, so the sweep redraws the edge without recomposing the card.
            .drawBehind {
                val x = size.width * (2 * sweep.value - 1)
                val brush = Brush.linearGradient(listOf(soft, bright, soft), start = Offset(x, 0f), end = Offset(x + size.width, size.height))
                drawRoundRect(brush, cornerRadius = CornerRadius(radius.toPx()))
            }
            .padding(1.dp)
            .clip(RoundedCornerShape(radius - 1.dp))
            .background(Theme[colors][surface]),
        content = content,
    )
}

/**
 * One overlapping dot per subagent, colored by how it stands: green done, accent at work. Beyond four, the
 * last says how many more. Silent: the title's "1 of 3 done" says it.
 */
@Composable
private fun TeamDots(team: List<SubagentStatus>) {
    val ring = Theme[colors][surface]
    val shown = if (team.size > MAX_TEAM_DOTS) team.take(MAX_TEAM_DOTS - 1) else team
    Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        shown.forEach { status ->
            val color = subagentDotColor(status)
            Box(
                Modifier
                    .size(22.dp)
                    .border(2.dp, ring, CircleShape)
                    .padding(2.dp)
                    .background(if (status.live && status != SubagentStatus.Running) color.copy(alpha = 0.6f) else color, CircleShape),
            )
        }
        if (team.size > MAX_TEAM_DOTS) {
            Box(
                Modifier.size(22.dp).border(2.dp, ring, CircleShape).padding(2.dp).background(Theme[colors][surface3], CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("+${team.size - shown.size}", style = Theme[typography][caption].copy(fontSize = 9.sp), color = Theme[colors][textSecondary], maxLines = 1)
            }
        }
    }
}

private const val MAX_TEAM_DOTS = 4

/** The card's top: the spinner tile, the step, the running time and Stop; then the plan's progress, when there's a plan. */
@Composable
private fun CardHeader(step: LiveStep, turnKey: String?, plan: TodoList?, hasDetails: Boolean, expanded: Boolean, onStop: () -> Unit, canStop: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = if (plan != null) 0.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step.team.isNotEmpty()) {
            TeamDots(step.team)
        } else {
            Box(
                Modifier
                    .size(30.dp)
                    .background(Theme[colors][accentSoft], RoundedCornerShape(Theme[radii][radiusSmall]))
                    // The title says it's working; "Loading" from the spinner would only repeat it.
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Spinner(Modifier.size(14.dp), color = Theme[colors][accentText])
            }
        }
        Column(Modifier.weight(1f)) {
            val progress = step.teamProgress
            Text(
                if (progress == null) {
                    AnnotatedString(step.title)
                } else {
                    buildAnnotatedString {
                        append(step.title)
                        withStyle(SpanStyle(color = Theme[colors][textTertiary], fontWeight = FontWeight.Normal)) { append(" · $progress") }
                    }
                },
                style = Theme[typography][label],
                color = Theme[colors][text],
                maxLines = if (progress == null) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            step.detail?.let {
                Text(
                    it,
                    style = Theme[typography][caption].copy(fontFamily = Theme[typography][code].fontFamily),
                    color = Theme[colors][accentText],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        turnKey?.let { key(it) { RunningTime() } }
        // Without a plan row below, the header says it opens.
        if (plan == null && hasDetails) DetailsChevron(expanded)
        StopButton(onStop, enabled = canStop)
    }
    if (plan != null) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlanBar(plan.done.toFloat() / plan.total.coerceAtLeast(1), Modifier.weight(1f))
            val inHand = planStep(plan)
            Text(
                if (inHand != null) "Step $inHand of ${plan.total}" else "${plan.done} of ${plan.total} done",
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
            )
            DetailsChevron(expanded)
        }
    }
}

@Composable
private fun DetailsChevron(expanded: Boolean) {
    UnstyledIcon(
        if (expanded) Lucide.ChevronDown else Lucide.ChevronUp,
        contentDescription = null,
        tint = Theme[colors][textMuted],
        modifier = Modifier.size(13.dp),
    )
}

/**
 * How long the turn has run since it showed here, "0:42", ticking on the second. Screen readers skip it:
 * read out, it would change under them every second.
 */
@Composable
private fun RunningTime() {
    val started = remember { Clock.System.now().toEpochMilliseconds() }
    var now by remember { mutableStateOf(started) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Clock.System.now().toEpochMilliseconds()
            // To the next whole second since the start, so no second is skipped or shown twice.
            delay(1_000 - (now - started) % 1_000)
        }
    }
    Text(
        runningTime((now - started) / 1_000),
        style = Theme[typography][caption].copy(fontFamily = Theme[typography][code].fontFamily),
        color = Theme[colors][textTertiary],
        modifier = Modifier.clearAndSetSemantics {},
    )
}

/** Seconds as a clock reads them: "0:42", "12:05", "1:02:03". */
internal fun runningTime(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val (h, m, sec) = Triple(s / 3600, s % 3600 / 60, s % 60)
    val mm = if (h > 0) m.toString().padStart(2, '0') else m.toString()
    return (if (h > 0) "$h:" else "") + "$mm:${sec.toString().padStart(2, '0')}"
}

/** Stops the task: a small round button, with the full touch size around it. */
@Composable
private fun StopButton(onStop: () -> Unit, enabled: Boolean) {
    UnstyledButton(
        onClick = onStop,
        enabled = enabled,
        modifier = Modifier.size(MinTouchTarget).clip(CircleShape).alpha(if (enabled) 1f else 0.45f),
        indication = rememberColoredIndication(Theme[colors][text]),
    ) {
        Box(Modifier.size(34.dp).background(Theme[colors][surface3], CircleShape), contentAlignment = Alignment.Center) {
            UnstyledIcon(Lucide.Square, contentDescription = "Stop the task", tint = Theme[colors][text], modifier = Modifier.size(12.dp))
        }
    }
}

/** The plan's progress: a thin track filling in the accent as steps are done. */
@Composable
private fun PlanBar(fraction: Float, modifier: Modifier) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "plan")
    val fill = Brush.horizontalGradient(listOf(Theme[colors][accent], Theme[colors][accentText]))
    Box(modifier.height(4.dp).clip(CircleShape).background(Theme[colors][surface3])) {
        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircleShape).background(fill))
    }
}

private const val SHIMMER_MS = 3_000

/** The dock's frosted card for a plan after its turn. */
@Composable
private fun FrostedPanel(hazeState: HazeState, content: @Composable ColumnScope.() -> Unit) {
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
            .border(1.dp, Theme[colors][strokeStrong], shape),
        content = content,
    )
}

@Composable
private fun TodoRow(item: TodoItem, depth: Int, live: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 20).dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp).size(16.dp), contentAlignment = Alignment.Center) {
            when (item.status) {
                // A past plan's step isn't still running, whatever it was marked.
                TodoStatus.InProgress -> if (live) Spinner(Modifier.size(13.dp)) else StatusIcon(Lucide.CircleDashed, Theme[colors][textTertiary])
                TodoStatus.Pending -> StatusIcon(Lucide.Circle, Theme[colors][textTertiary])
                TodoStatus.Completed -> StatusIcon(Lucide.CircleCheck, Theme[colors][success])
                TodoStatus.Cancelled -> StatusIcon(Lucide.CircleSlash, Theme[colors][textTertiary])
            }
        }
        Text(
            item.content,
            style = Theme[typography][bodySmall].let {
                if (item.status == TodoStatus.Cancelled) it.copy(textDecoration = TextDecoration.LineThrough) else it
            },
            color = when (item.status) {
                TodoStatus.InProgress -> Theme[colors][text]
                TodoStatus.Pending -> Theme[colors][textSecondary]
                TodoStatus.Completed, TodoStatus.Cancelled -> Theme[colors][textTertiary]
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color) {
    UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
}

private const val FINISHED_LINGER_MS = 4_000L

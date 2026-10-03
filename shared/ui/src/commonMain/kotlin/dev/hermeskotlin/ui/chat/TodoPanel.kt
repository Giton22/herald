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
import dev.hermeskotlin.core.chat.TodoItem
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
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
    ) {
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

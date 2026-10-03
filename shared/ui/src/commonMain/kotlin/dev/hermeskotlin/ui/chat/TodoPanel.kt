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
import androidx.compose.runtime.saveable.rememberSaveable
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
 * The agent's plan above the composer, ticking off as it works. A finished plan stays a moment so the
 * last check lands, then leaves; tap the header to fold it to one line.
 */
@Composable
fun TodoPanel(todos: TodoList?, hazeState: HazeState) {
    // A list seen finishing lingers briefly; one already finished when the chat opened stays hidden.
    var sawActive by remember { mutableStateOf(false) }
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(todos) {
        if (todos?.active == true) sawActive = true
        lingering = sawActive && todos != null && !todos.active
        if (lingering) {
            delay(FINISHED_LINGER_MS)
            lingering = false
        }
    }
    val visible = todos != null && todos.items.isNotEmpty() && (todos.active || lingering)
    // Kept through the exit animation, so the panel doesn't empty before it leaves.
    var shown by remember { mutableStateOf(todos) }
    if (visible) shown = todos
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        shown?.let { Panel(it, hazeState) }
    }
}

@Composable
private fun Panel(todos: TodoList, hazeState: HazeState) {
    var expanded by rememberSaveable { mutableStateOf(true) }
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
            Text("Tasks ${todos.done}/${todos.total}", style = Theme[typography][label], color = Theme[colors][text])
            Box(Modifier.weight(1f)) {
                // Folded, the step in hand still shows.
                if (!expanded && current != null) {
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
                todos.tree().forEach { (item, depth) -> TodoRow(item, depth) }
            }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem, depth: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 20).dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp).size(16.dp), contentAlignment = Alignment.Center) {
            when (item.status) {
                TodoStatus.InProgress -> Spinner(Modifier.size(13.dp))
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

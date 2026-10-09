package dev.hermeskotlin.ui.journey

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Sparkles
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.journey.JourneyNode
import dev.hermeskotlin.core.journey.JourneyNodeDetail
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/**
 * `/journey`: the skills the agent wrote and the memories it keeps, newest first by month, Desktop's
 * star map as a list. Tapping one shows its text.
 */
@Composable
fun JourneySheet(visible: Boolean, controller: JourneyController, onLoad: () -> Unit, onDismiss: () -> Unit) {
    val state = controller.state.collectAsStateWithLifecycle().value
    var open by remember { mutableStateOf<JourneyNode?>(null) }
    LaunchedEffect(visible) {
        if (visible) onLoad() else open = null
    }
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        val node = open
        if (node != null) {
            NodeDetail(node, controller, onBack = { open = null })
            return@BottomSheet
        }
        val graph = state.graph
        SheetHeader(
            "Journey",
            graph?.let { "${count(it.skills, "skill")} and ${count(it.memories, "memory", "memories")} this profile's agent has picked up." },
        )
        when {
            graph == null && state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
            graph == null -> Note(state.error ?: "Couldn't load the journey.", error = true)
            graph.nodes.isEmpty() -> Note("Nothing learned yet. Skills the agent writes and memories it saves show up here.")
            else -> {
                val months = remember(graph) {
                    graph.nodes.sortedByDescending { it.timestamp ?: Long.MIN_VALUE }.groupBy { node -> node.timestamp?.let(::monthLabel) ?: "Undated" }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    months.forEach { (month, nodes) ->
                        item(key = "m-$month") {
                            Text(
                                month.uppercase(),
                                style = Theme[typography][caption].copy(fontWeight = FontWeight.Bold, letterSpacing = 0.06.em),
                                color = Theme[colors][textTertiary],
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(nodes, key = { it.id }) { NodeRow(it, onClick = { open = it }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeRow(node: JourneyNode, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(
            if (node.isMemory) Lucide.Brain else Lucide.Sparkles,
            contentDescription = null,
            tint = Theme[colors][if (node.isMemory) textSecondary else accentText],
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(node.title, style = Theme[typography][body], color = Theme[colors][textColor], maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(node.detailLine(), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (node.pinned) UnstyledIcon(Lucide.Pin, contentDescription = "Pinned", tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun NodeDetail(node: JourneyNode, controller: JourneyController, onBack: () -> Unit) {
    val detail by produceState<Result<JourneyNodeDetail?>?>(null, node.id) { value = runCatching { controller.detail(node) } }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Lucide.ArrowLeft, contentDescription = "Back to the journey", onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(node.title, style = Theme[typography][body], color = Theme[colors][textColor], maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(node.detailLine(), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 1)
        }
    }
    val content = detail?.getOrNull()?.content
    when {
        detail == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
        content.isNullOrBlank() -> Note("Couldn't read this one.", error = true)
        else -> Box(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            SelectionContainer { MarkdownText(content) }
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = Theme[typography][bodySmall],
        color = Theme[colors][if (error) danger else textTertiary],
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

private fun JourneyNode.detailLine(): String = if (isMemory) {
    listOfNotNull("Memory", memorySource?.substringAfterLast('/')?.takeIf { it.isNotBlank() }).joinToString(" · ")
} else {
    listOfNotNull(
        "Skill",
        category?.takeIf { it.isNotBlank() && it != "uncategorized" },
        when (useCount) {
            0 -> null
            1 -> "used once"
            else -> "used $useCount times"
        },
        "archived".takeIf { state == "archived" },
    ).joinToString(" · ")
}

private fun count(n: Int, one: String, many: String = "${one}s"): String = "$n ${if (n == 1) one else many}"

private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** "October 2026" for epoch seconds, in UTC (Howard Hinnant's civil-from-days). */
internal fun monthLabel(epochSeconds: Long): String {
    val days = epochSeconds.floorDiv(86_400L)
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return "${MONTHS[month - 1]} $year"
}

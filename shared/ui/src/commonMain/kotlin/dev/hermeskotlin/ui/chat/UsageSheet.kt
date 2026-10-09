package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ContextBreakdown
import dev.hermeskotlin.core.chat.ContextFile
import dev.hermeskotlin.core.chat.SessionUsage
import dev.hermeskotlin.core.chat.compactCount
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.sessions.SessionTotals
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.heading as headingStyle
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

data class UsageSheetState(
    val totals: SessionTotals? = null,
    /** The provider account's limits and credits, as the gateway words them. */
    val limits: List<String> = emptyList(),
    /** What fills the context window, by category; null for a chat whose agent hasn't started. */
    val breakdown: ContextBreakdown? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** What the open chat has used: the stored session's totals and cost, and the account's limits. */
class UsageController(private val sessions: SessionsApi, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(UsageSheetState())
    val state: StateFlow<UsageSheetState> = _state.asStateFlow()

    private var chat: ChatSession? = null
    private var refresh: Job? = null

    fun load(gateway: GatewayUrl, sessionId: String?, profile: String?, chat: ChatSession?) {
        this.chat = chat
        refresh?.cancel()
        _state.value = UsageSheetState(loading = true)
        scope.launch {
            val limits = async { chat?.accountLimits().orEmpty() }
            val breakdown = async { chat?.contextBreakdown()?.takeUnless { it.isEmpty } }
            // A new chat has no stored row until its first prompt.
            val totals = sessionId?.let { sessions.totals(gateway, it, profile) }
            _state.value = UsageSheetState(
                totals = (totals as? ApiResult.Success)?.value,
                limits = limits.await(),
                breakdown = breakdown.await(),
                error = totals?.takeIf { it !is ApiResult.Success }?.let { it.errorMessage ?: "Couldn't load this chat's usage." },
            )
        }
    }

    /** Fetches the context split again, as a turn moves on, so an open sheet doesn't show an old one. */
    fun refreshBreakdown() {
        val chat = chat ?: return
        if (_state.value.loading) return
        refresh?.cancel()
        refresh = scope.launch {
            val breakdown = chat.contextBreakdown()?.takeUnless { it.isEmpty } ?: return@launch
            _state.update { if (it.loading) it else it.copy(breakdown = breakdown) }
        }
    }
}

@Composable
fun UsageSheet(visible: Boolean, controller: UsageController, live: SessionUsage?, onLoad: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(visible) { if (visible) onLoad() }
    // The context split is fetched, not streamed; follow the live count so it keeps up with the turn.
    LaunchedEffect(visible, live?.contextUsed) { if (visible) controller.refreshBreakdown() }
    UsageSheetView(visible, controller.state.collectAsStateWithLifecycle().value, live, onDismiss)
}

/** The usage sheet's layout, apart from its controller, so previews can draw it from sample data. */
@Composable
internal fun UsageSheetView(visible: Boolean, state: UsageSheetState, live: SessionUsage?, onDismiss: () -> Unit) {
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            Modifier
                // Large text or many files can outgrow the screen; the sheet then scrolls rather than cutting them off.
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when {
                state.loading -> {
                    WhoseUsage(model = null)
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { Spinner() }
                }
                else -> {
                    val totals = state.totals
                    if (totals != null) Totals(totals) else WhoseUsage(model = null)
                    state.error?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary]) }
                    when {
                        state.breakdown != null -> ContextSplit(state.breakdown)
                        else -> live?.takeIf { it.contextMax != null && it.contextUsed != null }?.let { Context(it) }
                    }
                    if (state.limits.isNotEmpty()) Limits(state.limits)
                    if (state.totals == null && state.error == null && live == null && state.limits.isEmpty()) {
                        Text("Nothing used yet.", style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
                    }
                }
            }
        }
    }
}

/** Whose usage this is: the sheet's heading, with the model in mono when it's known. It's read as "Usage, …". */
@Composable
private fun WhoseUsage(model: String?) {
    val spoken = "Usage, this chat" + (model?.let { ", $it" } ?: "")
    Text(
        buildAnnotatedString {
            append("This chat")
            if (model != null) {
                append(" · ")
                withStyle(Theme[typography][code].copy(fontSize = 12.sp).toSpanStyle()) { append(model) }
            }
        },
        style = Theme[typography][bodySmall].copy(fontSize = 13.sp),
        color = Theme[colors][textTertiary],
        modifier = Modifier.semantics {
            heading()
            contentDescription = spoken
        },
    )
}

/** The cost large, how it was worked out, the model calls beside it, then the tokens on tiles. */
@Composable
private fun Totals(totals: SessionTotals) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val cost = totals.costUsd
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                WhoseUsage(totals.model?.takeIf { it.isNotBlank() })
                val amount = when {
                    totals.costStatus == "included" -> "Included"
                    cost == null || totals.costStatus == "unknown" -> "—"
                    else -> usd(cost)
                }
                // One line, however large the text: it shrinks to fit rather than breaking inside the number.
                BasicText(
                    amount,
                    style = Theme[typography][display].copy(
                        fontSize = 40.sp,
                        lineHeight = 1.05.em,
                        fontWeight = FontWeight.SemiBold,
                        color = Theme[colors][text],
                    ),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 40.sp),
                    modifier = Modifier.semantics { contentDescription = "Cost, $amount" },
                )
                Text(
                    when {
                        totals.costStatus == "included" -> "Covered by your plan with the provider"
                        cost == null || totals.costStatus == "unknown" -> "No price known for this model"
                        totals.costIsEstimate -> "Estimated from the model's list prices"
                        else -> "As billed by the provider"
                    },
                    style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                    color = Theme[colors][textTertiary],
                )
            }
            totals.apiCalls?.takeIf { it > 0 }?.let { calls ->
                // At most a third of the row, so the cost keeps the rest.
                Column(Modifier.widthIn(max = 120.dp).semantics(mergeDescendants = true) { }, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(compactCount(calls), style = Theme[typography][code].copy(fontSize = 20.sp, fontWeight = FontWeight.Medium), color = Theme[colors][text])
                    Text(if (calls == 1L) "model call" else "model calls", style = Theme[typography][caption].copy(fontSize = 12.sp), color = Theme[colors][textTertiary])
                }
            }
        }
        // Name on the tile, then what a screen reader says.
        val stats = buildList {
            add(Triple("Input", "Input", totals.inputTokens ?: 0))
            add(Triple("Output", "Output", totals.outputTokens ?: 0))
            // The rest only when the model used them.
            totals.reasoningTokens?.takeIf { it > 0 }?.let { add(Triple("Reasoning", "Reasoning", it)) }
            totals.cacheReadTokens?.takeIf { it > 0 }?.let { add(Triple("From cache", "Read from cache", it)) }
            totals.cacheWriteTokens?.takeIf { it > 0 }?.let { add(Triple("To cache", "Written to cache", it)) }
        }
        // Four across, or two when large text would leave a tile too narrow for its number.
        val perRow = if (LocalDensity.current.fontScale > 1.3f) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            stats.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (name, spoken, value) -> StatTile(name, spoken, value, Modifier.weight(1f).fillMaxHeight()) }
                    // A short last row keeps the tiles the width of the ones above.
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A token count in mono over what it counts, on a raised tile; read as "Input, 184k tokens". */
@Composable
private fun StatTile(name: String, spoken: String, value: Long, modifier: Modifier) {
    Column(
        modifier
            .background(Theme[colors][surface2], RoundedCornerShape(Theme[radii][radiusMedium]))
            .padding(10.dp)
            .clearAndSetSemantics { contentDescription = "$spoken, ${compactCount(value)} tokens" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Shrinks to fit the tile rather than losing its last letter: "184k" must never read as "184".
        BasicText(
            compactCount(value),
            style = Theme[typography][code].copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Theme[colors][text]),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 15.sp),
        )
        Text(name, style = Theme[typography][caption].copy(fontSize = 11.5.sp), color = Theme[colors][textTertiary])
    }
}

/**
 * "Context window" and how full it is, in mono at the end, or under it when there isn't room. [spoken] is
 * the amount in words, so "/" isn't read out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextHeader(amount: String, spoken: String) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        Text("Context window", style = Theme[typography][headingStyle], color = Theme[colors][text], modifier = Modifier.padding(end = 12.dp).semantics { heading() })
        Text(
            amount,
            style = Theme[typography][code].copy(fontSize = 12.sp),
            color = Theme[colors][textSecondary],
            modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        )
    }
}

/** How full the model's context window is now; compression frees it up. */
@Composable
private fun Context(live: SessionUsage) {
    val used = live.contextUsed ?: return
    val max = live.contextMax ?: return
    val fraction = (used.toFloat() / max).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val percent = live.contextPercent ?: (fraction * 100).toInt()
        ContextHeader(
            "${compactCount(used)} / ${compactCount(max)} · $percent%",
            "${compactCount(used)} of ${compactCount(max)} tokens, $percent percent",
        )
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Theme[colors][surface3])) {
            Box(
                Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape)
                    .background(if (fraction >= CONTEXT_WARNING) Theme[colors][warning] else Theme[colors][accent]),
            )
        }
        live.cacheHitPercent?.let {
            Text("$it% of the prompt came from cache", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
    }
}

/**
 * The context window split by what fills it, as `/context` shows it: one bar in segments, then each
 * category with its tokens, and the files the agent read in.
 */
@Composable
private fun ContextSplit(breakdown: ContextBreakdown) {
    val max = breakdown.max.takeIf { it > 0 } ?: breakdown.used.coerceAtLeast(1)
    val shown = breakdown.categories.filter { it.tokens > 0 }
    val total = shown.sumOf { it.tokens }.coerceAtLeast(1)
    // The categories are estimates and the total may be measured; scale them to fill the used share.
    val usedFraction = (breakdown.used.toFloat() / max).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val percent = (usedFraction * 100).toInt()
        ContextHeader(
            "${if (breakdown.estimated) "~" else ""}${compactCount(breakdown.used)} / ${compactCount(max)} · $percent%",
            "${if (breakdown.estimated) "About " else ""}${compactCount(breakdown.used)} of ${compactCount(max)} tokens, $percent percent",
        )
        Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Theme[colors][surface3]), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            shown.forEach { category ->
                val weight = usedFraction * category.tokens / total
                if (weight > 0f) Box(Modifier.weight(weight).fillMaxHeight().background(contextColor(category.id)))
            }
            if (usedFraction < 1f) Box(Modifier.weight(1f - usedFraction))
        }
        // The legend in two columns, each category with its swatch and tokens; one column at large text, so
        // the labels don't break inside words.
        val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.chunked(columns).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    group.forEach { category -> LegendItem(category.label, category.tokens, contextColor(category.id), Modifier.weight(1f)) }
                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    val files = breakdown.files.filter { it.tokens > 0 || !it.loaded }
    if (files.isNotEmpty()) FilesReadIn(files)
}

@Composable
private fun LegendItem(label: String, tokens: Long, color: Color, modifier: Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = Theme[typography][bodySmall].copy(fontSize = 13.sp), color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
        Text(compactCount(tokens), style = Theme[typography][code].copy(fontSize = 12.sp), color = Theme[colors][textTertiary])
    }
}

/** The files the agent read in, on a raised card: each in mono with its tokens, or that it was skipped. */
@Composable
private fun FilesReadIn(files: List<ContextFile>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface2], RoundedCornerShape(Theme[radii][radiusMedium]))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            "FILES READ IN",
            style = Theme[typography][eyebrow],
            color = Theme[colors][textTertiary],
            modifier = Modifier
                .padding(top = 8.dp, bottom = 4.dp)
                .semantics {
                    heading()
                    contentDescription = "Files read in"
                },
        )
        files.forEach { file ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 34.dp).semantics(mergeDescendants = true) { },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnstyledIcon(Lucide.FileText, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
                Text(
                    file.label,
                    style = Theme[typography][code].copy(fontSize = 12.5.sp),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (file.loaded) compactCount(file.tokens) else "skipped",
                    style = Theme[typography][code].copy(fontSize = 12.sp),
                    color = Theme[colors][textTertiary],
                )
            }
        }
    }
}

/**
 * A fixed color per category, in `/context`'s order: the validated eight-hue categorical palette,
 * stepped separately for light and dark surfaces (Desktop's CSS variables don't travel). Every
 * slice also has its label and count beside it, so color is never the only cue.
 */
@Composable
private fun contextColor(id: String): Color {
    val dark = Theme[colors][background].luminance() < 0.5f
    val slot = CONTEXT_ORDER.indexOf(id)
    return if (slot < 0) Theme[colors][textTertiary] else (if (dark) CONTEXT_DARK else CONTEXT_LIGHT)[slot]
}

private val CONTEXT_ORDER = listOf(
    "system_prompt", "tool_definitions", "rules", "skills", "mcp", "subagent_definitions", "memory", "conversation",
)
private val CONTEXT_LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF4A3AA7, 0xFFE34948).map(::Color)
private val CONTEXT_DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767).map(::Color)

@Composable
private fun Limits(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Account", style = Theme[typography][label], color = Theme[colors][text], modifier = Modifier.semantics { heading() })
        lines.forEach { Text(it, style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textTertiary]) }
    }
}

/** "$0.42", "$12.30", "<$0.01". */
internal fun usd(amount: Double): String {
    if (amount > 0 && amount < 0.01) return "<$0.01"
    val cents = (amount * 100).roundToLong()
    return "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

private const val CONTEXT_WARNING = 0.8f

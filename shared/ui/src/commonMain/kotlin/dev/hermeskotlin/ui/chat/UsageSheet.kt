package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ContextBreakdown
import dev.hermeskotlin.core.chat.SessionUsage
import dev.hermeskotlin.core.chat.compactCount
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.sessions.SessionTotals
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
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
        SheetHeader("Usage", state.totals?.model?.takeIf { it.isNotBlank() }?.let { "This chat · $it" } ?: "This chat")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { Spinner() }
                else -> {
                    state.totals?.let { Totals(it) }
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

@Composable
private fun Totals(totals: SessionTotals) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val cost = totals.costUsd
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when {
                    totals.costStatus == "included" -> "Included"
                    cost == null || totals.costStatus == "unknown" -> "—"
                    else -> usd(cost)
                },
                style = Theme[typography][title],
                color = Theme[colors][text],
            )
            Text(
                when {
                    totals.costStatus == "included" -> "Covered by your plan with the provider"
                    cost == null || totals.costStatus == "unknown" -> "No price known for this model"
                    totals.costIsEstimate -> "Estimated from the model's list prices"
                    else -> "As billed by the provider"
                },
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("Input", totals.inputTokens ?: 0)
            Stat("Output", totals.outputTokens ?: 0)
            // The rest only when the model used them.
            totals.reasoningTokens?.takeIf { it > 0 }?.let { Stat("Reasoning", it) }
            totals.cacheReadTokens?.takeIf { it > 0 }?.let { Stat("Read from cache", it) }
            totals.cacheWriteTokens?.takeIf { it > 0 }?.let { Stat("Written to cache", it) }
            totals.apiCalls?.takeIf { it > 0 }?.let { Stat("Model calls", it, tokens = false) }
        }
    }
}

@Composable
private fun Stat(name: String, value: Long, tokens: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Theme[typography][body], color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
        Text(
            if (tokens) "${compactCount(value)} tokens" else compactCount(value),
            style = Theme[typography][body],
            color = Theme[colors][text],
        )
    }
}

/** How full the model's context window is now; compression frees it up. */
@Composable
private fun Context(live: SessionUsage) {
    val used = live.contextUsed ?: return
    val max = live.contextMax ?: return
    val fraction = (used.toFloat() / max).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Context window", style = Theme[typography][label], color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
            Text(
                "${compactCount(used)} of ${compactCount(max)} · ${live.contextPercent ?: (fraction * 100).toInt()}%",
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
            )
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Theme[colors][stroke])) {
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Context window", style = Theme[typography][label], color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
            Text(
                "${if (breakdown.estimated) "~" else ""}${compactCount(breakdown.used)} of ${compactCount(max)} · ${(usedFraction * 100).toInt()}%",
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
            )
        }
        Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Theme[colors][stroke]), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            shown.forEach { category ->
                val weight = usedFraction * category.tokens / total
                if (weight > 0f) Box(Modifier.weight(weight).fillMaxHeight().background(contextColor(category.id)))
            }
            if (usedFraction < 1f) Box(Modifier.weight(1f - usedFraction))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEach { category ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(contextColor(category.id)))
                    Text(category.label, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
                    Text(compactCount(category.tokens), style = Theme[typography][bodySmall], color = Theme[colors][text])
                }
            }
        }
        val files = breakdown.files.filter { it.tokens > 0 || !it.loaded }
        if (files.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Files read in", style = Theme[typography][label], color = Theme[colors][textSecondary])
                files.forEach { file ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(file.label, style = Theme[typography][bodySmall], color = Theme[colors][text], modifier = Modifier.weight(1f), maxLines = 1)
                        Text(
                            if (file.loaded) compactCount(file.tokens) else "skipped",
                            style = Theme[typography][bodySmall],
                            color = Theme[colors][textTertiary],
                        )
                    }
                }
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Account", style = Theme[typography][label], color = Theme[colors][textSecondary])
        lines.forEach { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][text]) }
    }
}

/** "$0.42", "$12.30", "<$0.01". */
internal fun usd(amount: Double): String {
    if (amount > 0 && amount < 0.01) return "<$0.01"
    val cents = (amount * 100).roundToLong()
    return "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

private const val CONTEXT_WARNING = 0.8f

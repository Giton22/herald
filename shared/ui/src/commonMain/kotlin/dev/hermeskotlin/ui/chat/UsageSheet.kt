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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.SessionUsage
import dev.hermeskotlin.core.chat.compactCount
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.sessions.SessionTotals
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.designsystem.accent
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
    val loading: Boolean = false,
    val error: String? = null,
)

/** What the open chat has used: the stored session's totals and cost, and the account's limits. */
class UsageController(private val sessions: SessionsApi, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(UsageSheetState())
    val state: StateFlow<UsageSheetState> = _state.asStateFlow()

    fun load(gateway: GatewayUrl, sessionId: String?, profile: String?, chat: ChatSession?) {
        _state.value = UsageSheetState(loading = true)
        scope.launch {
            val limits = async { chat?.accountLimits().orEmpty() }
            // A new chat has no stored row until its first prompt.
            val totals = sessionId?.let { sessions.totals(gateway, it, profile) }
            _state.value = UsageSheetState(
                totals = (totals as? ApiResult.Success)?.value,
                limits = limits.await(),
                error = totals?.takeIf { it !is ApiResult.Success }?.let { it.errorMessage ?: "Couldn't load this chat's usage." },
            )
        }
    }
}

@Composable
fun UsageSheet(visible: Boolean, controller: UsageController, live: SessionUsage?, onLoad: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(visible) { if (visible) onLoad() }
    val state = controller.state.collectAsStateWithLifecycle().value
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        SheetHeader("Usage", state.totals?.model?.takeIf { it.isNotBlank() }?.let { "This chat · $it" } ?: "This chat")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { Spinner() }
                else -> {
                    state.totals?.let { Totals(it) }
                    state.error?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary]) }
                    live?.takeIf { it.contextMax != null && it.contextUsed != null }?.let { Context(it) }
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

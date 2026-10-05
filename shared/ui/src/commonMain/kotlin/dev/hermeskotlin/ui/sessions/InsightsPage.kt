package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.compactCount
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.insights.UsageDay
import dev.hermeskotlin.core.insights.UsageReport
import dev.hermeskotlin.core.insights.dailySeries
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.chat.usd
import dev.hermeskotlin.ui.components.EmptyState
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

/**
 * The sidebar's Insights page, after Desktop's: what the profile's agent used over 7, 30 or 90 days.
 * Headline numbers first, then tokens by day (tap a day for its numbers), then the models, tools
 * and skills it used most.
 */
@Composable
internal fun InsightsPage(
    gateway: SavedGateway,
    profile: String?,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
    viewModel: InsightsViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(gateway, profile) { viewModel.bind(gateway, profile) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    // Consume before the callback: the route changes on it, and the view model outlives the screen,
    // so a stale flag must not bounce the next mount after a fresh sign-in.
    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) {
            viewModel.consumeSessionExpired()
            onSessionExpired()
        }
    }
    InsightsView(state, onBack = onBack, onSelectPeriod = viewModel::selectPeriod, onRetry = viewModel::refresh)
}

/** The Insights page's layout, apart from its view model, so previews can draw it from sample data. */
@Composable
internal fun InsightsView(state: InsightsUiState, onBack: () -> Unit, onSelectPeriod: (InsightsPeriod) -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SubpageHeader("Insights", onBack = onBack)
        SegmentedControl(
            options = InsightsPeriod.entries,
            selected = state.period,
            onSelect = onSelectPeriod,
            optionLabel = { it.label },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        )
        val report = state.report
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                report == null && state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load insights", state.error) {
                    Button("Try again", onClick = onRetry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                }
                report == null -> CenteredSpinner()
                report.totals.sessions == 0 && report.totals.apiCalls == 0 ->
                    EmptyState(Lucide.ChartColumn, "Nothing used yet", "Chats from this period, and what they cost, show up here.")
                else -> Report(report)
            }
        }
    }
}

@Composable
private fun Report(report: UsageReport) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        val totals = report.totals
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(usd(totals.cost), if (totals.actualCost > 0) "billed" else "estimated cost", Modifier.weight(1f))
            Tile(compactCount(totals.sessions.toLong()), "sessions", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(compactCount(totals.input + totals.output), "tokens in and out", Modifier.weight(1f))
            Tile(totals.cachePercent?.let { "$it%" } ?: "—", "prompt from cache", Modifier.weight(1f))
        }
        DailyChart(report)
        Ranked(
            "Models",
            report.byModel.sortedByDescending { it.tokens }.map { displayModelName(it.model) to it.tokens },
            format = { "${compactCount(it)} tokens" },
        )
        Ranked("Tools", report.tools.sortedByDescending { it.count }.map { it.tool to it.count.toLong() }, format = { "${compactCount(it)} calls" })
        Ranked("Skills", report.topSkills.sortedByDescending { it.count }.map { it.skill to it.count.toLong() }, format = { "${compactCount(it)} uses" })
    }
}

/** A headline number with its caption. */
@Composable
private fun Tile(value: String, caption: String, modifier: Modifier) {
    Column(
        modifier.background(Theme[colors][stroke], RoundedCornerShape(Theme[radii][radiusMedium])).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = Theme[typography][title], color = Theme[colors][text], maxLines = 1)
        Text(caption, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 1)
    }
}

/**
 * Tokens per day as one series of thin bars on a shared baseline, empty days included. A tap picks a
 * day and its numbers replace the caption; the picked bar keeps full color and the rest recede.
 */
@Composable
private fun DailyChart(report: UsageReport) {
    val days = remember(report) { report.dailySeries(Clock.System.now().toEpochMilliseconds() / 86_400_000) }
    val peak = days.maxOf { it.tokens }.coerceAtLeast(1)
    var picked by remember(report) { mutableStateOf<UsageDay?>(null) }
    val gap = if (days.size > 45) 1.dp else 2.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Tokens by day")
        Text(
            picked?.let { day ->
                listOf(
                    shortDate(day.day),
                    "${compactCount(day.tokens)} tokens",
                    usd(day.cost),
                    "${day.sessions} ${if (day.sessions == 1) "session" else "sessions"}",
                ).joinToString(" · ")
            } ?: "Peak ${compactCount(peak)} tokens. Tap a day for its numbers.",
            style = Theme[typography][bodySmall],
            color = Theme[colors][if (picked != null) text else textSecondary],
        )
        Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
            days.forEach { day ->
                val fraction = day.tokens.toFloat() / peak
                val dim = picked != null && picked != day
                // The whole column is the hit target, not just the bar.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            picked = if (picked == day) null else day
                        }
                        .semantics { contentDescription = "${shortDate(day.day)}: ${compactCount(day.tokens)} tokens" },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (day.tokens > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                                .background(
                                    Theme[colors][accent].copy(alpha = if (dim) 0.35f else 1f),
                                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                ),
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][strokeStrong]))
        Row(Modifier.fillMaxWidth()) {
            Text(shortDate(days.first().day), style = Theme[typography][caption], color = Theme[colors][textTertiary], modifier = Modifier.weight(1f))
            Text(shortDate(days.last().day), style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
    }
}

/** A top-five list with a bar per row scaled to the leader; the label and value carry the meaning. */
@Composable
private fun Ranked(title: String, rows: List<Pair<String, Long>>, format: (Long) -> String) {
    val shown = rows.filter { it.second > 0 }.take(5)
    if (shown.isEmpty()) return
    val top = shown.first().second.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title)
        shown.forEach { (name, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = Theme[typography][body], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(format(value), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(Theme[colors][stroke], RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(value.toFloat() / top).fillMaxHeight().background(Theme[colors][accent], RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = Theme[typography][caption], color = Theme[colors][textTertiary])
}

/** "2026-10-03" → "Oct 3". */
internal fun shortDate(iso: String): String {
    val parts = iso.split('-')
    val month = parts.getOrNull(1)?.toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: return iso
    val day = parts.getOrNull(2)?.toIntOrNull() ?: return iso
    return "$month $day"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.heading as headingStyle
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.chat.usd
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.ReportSkeleton
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
    InsightsView(state, place = gateway.label, onBack = onBack, onSelectPeriod = viewModel::selectPeriod, onRetry = viewModel::refresh)
}

/** The Insights page's layout, apart from its view model, so previews can draw it from sample data. */
@Composable
internal fun InsightsView(
    state: InsightsUiState,
    onBack: () -> Unit,
    onSelectPeriod: (InsightsPeriod) -> Unit,
    onRetry: () -> Unit,
    /** The gateway the numbers come from, said under the title. */
    place: String? = null,
) {
    val report = state.report
    val days = remember(report) { report?.dailySeries(Clock.System.now().toEpochMilliseconds() / 86_400_000) }
    Column(Modifier.fillMaxSize()) {
        // The period sits in the back row, as in the design; the title says which days it covers and where.
        SubpageHeader(
            "Insights",
            onBack = onBack,
            subtitle = listOfNotNull(
                days?.takeIf { it.isNotEmpty() }?.let { "${shortDate(it.first().day)} – ${shortDate(it.last().day)}" },
                place,
            ).joinToString(" · ").ifEmpty { null },
        ) {
            SegmentedControl(
                options = InsightsPeriod.entries,
                selected = state.period,
                onSelect = onSelectPeriod,
                optionLabel = { it.label },
                modifier = Modifier.widthIn(max = 228.dp).weight(1f, fill = false),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                report == null && state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load insights", state.error, error = true) {
                    Button("Try again", onClick = onRetry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                }
                report == null -> ReportSkeleton()
                report.totals.sessions == 0 && report.totals.apiCalls == 0 ->
                    EmptyState(Lucide.ChartColumn, "Nothing used yet", "Chats from this period, and what they cost, show up here.")
                else -> Report(report, days.orEmpty())
            }
        }
    }
}

@Composable
private fun Report(report: UsageReport, days: List<UsageDay>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val totals = report.totals
        val cost = usd(totals.cost)
        val costCaption = if (totals.actualCost > 0) "billed" else "estimated cost"
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Shrinks to fit rather than wrapping mid-number.
            BasicText(
                cost,
                style = Theme[typography][display].copy(color = Theme[colors][text], fontWeight = FontWeight.SemiBold, letterSpacing = (-0.035).em),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 44.sp),
            )
            Text(costCaption, style = Theme[typography][bodySmall].copy(fontSize = 13.sp), color = Theme[colors][textTertiary])
        }
        val tiles = listOf(
            compactCount(totals.sessions.toLong()) to "sessions",
            compactCount(totals.input + totals.output) to "tokens",
            (totals.cachePercent?.let { "$it%" } ?: "—") to "from cache",
        )
        // Three across, or one a row once a large font would crush them.
        if (LocalDensity.current.fontScale > 1.3f) {
            tiles.forEach { (value, caption) -> Tile(value, caption, Modifier.fillMaxWidth()) }
        } else {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tiles.forEach { (value, caption) -> Tile(value, caption, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        if (days.isNotEmpty()) DailyChart(report, days)
        Ranked(
            "Models",
            report.byModel.sortedByDescending { it.tokens }.map { displayModelName(it.model) to it.tokens },
            format = { "${compactCount(it)} tokens" },
        )
        Chips("Tools", report.tools.sortedByDescending { it.count }.map { it.tool to it.count.toLong() }, unit = "calls")
        Chips("Skills", report.topSkills.sortedByDescending { it.count }.map { it.skill to it.count.toLong() }, unit = "uses")
    }
}

/** A headline number with its caption, on a ringed surface. */
@Composable
private fun Tile(value: String, note: String, modifier: Modifier) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        modifier
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BasicText(
            value,
            style = Theme[typography][title].copy(color = Theme[colors][text], fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 20.sp),
        )
        Text(note, style = Theme[typography][caption].copy(fontSize = 12.sp), color = Theme[colors][textTertiary], maxLines = 2)
    }
}

/**
 * Tokens per day as one series of thin bars on a shared baseline in a card, empty days included, the peak
 * day brighter. A tap picks a day and its numbers show under the title; the rest recede.
 */
@Composable
private fun DailyChart(report: UsageReport, days: List<UsageDay>) {
    val peakDay = days.maxBy { it.tokens }
    val peak = peakDay.tokens.coerceAtLeast(1)
    var picked by remember(report) { mutableStateOf<UsageDay?>(null) }
    val gap = if (days.size > 45) 1.dp else 3.dp
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier.fillMaxWidth().background(Theme[colors][surface], shape).border(1.dp, Theme[colors][stroke], shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tokens by day", style = Theme[typography][headingStyle],color = Theme[colors][text], modifier = Modifier.semantics { heading() })
            if (peakDay.tokens > 0) {
                Text(
                    "Peak ${compactCount(peakDay.tokens)} · ${shortDate(peakDay.day)}",
                    style = Theme[typography][caption].copy(fontSize = 12.sp),
                    color = Theme[colors][textTertiary],
                )
            }
        }
        picked?.let { day ->
            Text(
                listOf(
                    shortDate(day.day),
                    "${compactCount(day.tokens)} tokens",
                    usd(day.cost),
                    "${day.sessions} ${if (day.sessions == 1) "session" else "sessions"}",
                ).joinToString(" · "),
                style = Theme[typography][bodySmall],
                color = Theme[colors][text],
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
            days.forEach { day ->
                val fraction = day.tokens.toFloat() / peak
                val dim = picked != null && picked != day
                val color = if (day == peakDay) Theme[colors][accentText] else Theme[colors][accent]
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
                                    color.copy(alpha = if (dim) 0.35f else 1f),
                                    RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.dp, bottomEnd = 1.dp),
                                ),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val dates = Theme[typography][code].copy(fontSize = 11.sp)
            Text(shortDate(days.first().day), style = dates, color = Theme[colors][textMuted], modifier = Modifier.weight(1f))
            Text(shortDate(days.last().day), style = dates, color = Theme[colors][textMuted])
        }
    }
}

/** A top-five list with a bar per row scaled to the leader; the label and value carry the meaning. */
@Composable
private fun Ranked(title: String, rows: List<Pair<String, Long>>, format: (Long) -> String) {
    val shown = rows.filter { it.second > 0 }.take(5)
    if (shown.isEmpty()) return
    val top = shown.first().second.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title)
        shown.forEach { (name, value) ->
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = Theme[typography][body].copy(fontSize = 14.sp), color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(format(value), style = Theme[typography][code].copy(fontSize = 12.sp), color = Theme[colors][textSecondary])
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(Theme[colors][surface3], CircleShape)) {
                    Box(Modifier.fillMaxWidth(value.toFloat() / top).fillMaxHeight().background(Theme[colors][accentText], CircleShape))
                }
            }
        }
    }
}

/** The most used, as mono chips with their counts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(title: String, rows: List<Pair<String, Long>>, unit: String) {
    val shown = rows.filter { it.second > 0 }.take(8)
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { (name, value) ->
                Row(
                    Modifier
                        .heightIn(min = 30.dp)
                        .background(Theme[colors][surface2], CircleShape)
                        .border(1.dp, Theme[colors][stroke], CircleShape)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clearAndSetSemantics { contentDescription = "$name, ${compactCount(value)} $unit" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val mono = Theme[typography][code].copy(fontSize = 12.sp)
                    Text(name, style = mono, color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(compactCount(value), style = mono, color = Theme[colors][textTertiary], maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = Theme[typography][eyebrow],
        color = Theme[colors][textTertiary],
        modifier = Modifier.semantics {
            heading()
            contentDescription = text
        },
    )
}

/** "2026-10-03" → "Oct 3". */
internal fun shortDate(iso: String): String {
    val parts = iso.split('-')
    val month = parts.getOrNull(1)?.toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: return iso
    val day = parts.getOrNull(2)?.toIntOrNull() ?: return iso
    return "$month $day"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

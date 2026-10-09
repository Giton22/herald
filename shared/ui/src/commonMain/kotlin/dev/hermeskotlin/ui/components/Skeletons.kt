package dev.hermeskotlin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Skeleton
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.surface2

/** A page of numbers on its way: a headline figure, three tiles, a chart and a few bars, as Insights lays them out. */
@Composable
fun ReportSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Skeleton(Modifier.width(160.dp).height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { Skeleton(Modifier.weight(1f).height(64.dp), RoundedCornerShape(Theme[radii][radiusMedium])) }
        }
        Skeleton(Modifier.fillMaxWidth().height(180.dp), RoundedCornerShape(Theme[radii][radiusLarge]))
        listOf(0.4f, 0.28f, 0.18f).forEach { width ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth(width).height(12.dp).background(Theme[colors][surface2], CircleShape))
                Box(Modifier.fillMaxWidth().height(6.dp).background(Theme[colors][surface2], CircleShape))
            }
        }
    }
}

/** A list on its way: [rows] stand-in rows, each a round mark and a line. */
@Composable
fun ListSkeleton(rows: Int = 6) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(rows) { i ->
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Skeleton(Modifier.size(28.dp), CircleShape)
                Skeleton(Modifier.fillMaxWidth(LINE_WIDTHS[i % LINE_WIDTHS.size]).height(14.dp), CircleShape)
            }
        }
    }
}

private val LINE_WIDTHS = listOf(0.7f, 0.5f, 0.85f, 0.6f, 0.4f, 0.75f)

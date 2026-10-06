package dev.hermeskotlin.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BatteryFull
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.SignalHigh
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Wifi
import com.composeunstyled.UnstyledIcon
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

@Composable
fun T(
    text: String,
    style: TextStyle,
    color: Color = S.text,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(text, modifier, style.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun T(text: AnnotatedString, style: TextStyle, color: Color = S.text, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Small pixel capitals, optionally behind the checker mark. */
@Composable
fun PixelLabel(text: String, color: Color = S.text3, modifier: Modifier = Modifier, checker: Boolean = false, trailing: String? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (checker) Checker(color, 7.dp)
        T(text.uppercase(), Type.pixel, color)
        if (trailing != null) T(trailing, Type.pixel, S.text4)
    }
}

@Composable
fun Icon(icon: ImageVector, tint: Color = S.text2, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

enum class PillKind { Primary, Secondary, Outline, Ghost, Danger }

@Composable
fun Pill(
    text: String,
    kind: PillKind = PillKind.Secondary,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    small: Boolean = false,
) {
    val (bg, fg, stroke) = when (kind) {
        PillKind.Primary -> Triple(S.signal, S.onSignal, Color.Transparent)
        PillKind.Secondary -> Triple(S.s3, S.text, Color.Transparent)
        PillKind.Outline -> Triple(Color.Transparent, S.text, S.line2)
        PillKind.Ghost -> Triple(Color.Transparent, S.text2, Color.Transparent)
        PillKind.Danger -> Triple(S.bad, Color.White, Color.Transparent)
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, stroke, CircleShape)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (small) 7.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, fg, if (small) 14.dp else 16.dp)
        T(text, Type.label.copy(fontSize = if (small) Type.label.fontSize * 0.92f else Type.label.fontSize), fg, maxLines = 1)
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    color: Color = S.s1,
    stroke: Color = S.line,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Box(modifier.clip(shape).background(color).border(1.dp, stroke, shape).padding(padding)) { content() }
}

@Composable
fun Dot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun StatusBar(time: String = "9:41", tint: Color = S.text) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(time, Type.label.copy(fontSize = Type.label.fontSize * 1.05f), tint)
        Spacer(Modifier.weight(1f))
        Icon(Lucide.SignalHigh, tint, 16.dp)
        Spacer(Modifier.width(6.dp))
        Icon(Lucide.Wifi, tint, 16.dp)
        Spacer(Modifier.width(6.dp))
        Icon(Lucide.BatteryFull, tint, 18.dp)
    }
}

/** A phone screen: status bar, content, gesture bar. */
@Composable
fun Phone(background: Color = S.bg, statusTint: Color = S.text, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(background)) {
        content()
        Box(Modifier.align(Alignment.TopCenter)) { StatusBar(tint = statusTint) }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).width(108.dp).height(4.dp)
                .clip(CircleShape).background(statusTint.copy(alpha = 0.8f)),
        )
    }
}

fun frosted(page: Color) = HazeBlurStyle {
    blurEnabled(true)
    blurRadius(24.dp)
    backgroundColor(page)
}

/** Frosted glass over whatever [hazeState] collects, tinted with [tint]. */
@Composable
fun Modifier.glass(hazeState: HazeState, shape: Shape, tint: Color = S.s2.copy(alpha = 0.72f)): Modifier =
    this.clip(shape)
        .hazeBlur(input = HazeInput.Sources(hazeState), style = frosted(S.bg))
        .background(tint)
        .border(1.dp, S.line2.copy(alpha = 0.8f), shape)

enum class Tab(val label: String, val icon: ImageVector) {
    Inbox("Inbox", Lucide.Inbox),
    Chats("Chats", Lucide.MessagesSquare),
    Agents("Agents", Lucide.Bot),
    Jobs("Jobs", Lucide.CalendarClock),
}

/** The floating glass tab bar and the compose button beside it. */
@Composable
fun TabDock(hazeState: HazeState, selected: Tab, modifier: Modifier = Modifier, badge: Map<Tab, Int> = emptyMap()) {
    Row(modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.weight(1f).height(62.dp).glass(hazeState, CircleShape).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                val on = tab == selected
                Column(
                    Modifier.weight(1f).height(50.dp).clip(CircleShape).background(if (on) S.s3 else Color.Transparent),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(tab.icon, if (on) S.text else S.text3, 20.dp)
                        badge[tab]?.let { n ->
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(15.dp).clip(CircleShape).background(S.ember),
                                contentAlignment = Alignment.Center,
                            ) { T("$n", Type.monoSmall.copy(fontSize = Type.monoSmall.fontSize * 0.85f), S.onSignal) }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    T(tab.label, Type.small.copy(fontSize = Type.small.fontSize * 0.82f), if (on) S.text else S.text3)
                }
            }
        }
        Box(Modifier.size(62.dp).clip(CircleShape).background(S.signal), contentAlignment = Alignment.Center) {
            Icon(Lucide.SquarePen, S.onSignal, 22.dp)
        }
    }
}

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

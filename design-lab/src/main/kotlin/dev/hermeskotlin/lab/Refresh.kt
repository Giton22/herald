package dev.hermeskotlin.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Wrench
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** The current look, and four refreshes of it. Same screens, same content, same colors. */
enum class Look(val title: String) {
    Current("Current"),
    Soft("A · Soft"),
    Glass("B · Glass"),
    Pixel("C · Pixel"),
    Cards("D · Cards"),
}

internal val Look.font: FontFamily get() = if (this == Look.Cards) Geist else Roboto
internal val Look.monoFont: FontFamily get() = if (this == Look.Cards) GeistMono else RobotoMono
internal val Look.rounded: Boolean get() = this == Look.Soft || this == Look.Glass || this == Look.Cards

internal fun Look.body() = TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = if (this == Look.Cards) (-0.1).sp else 0.sp)
internal fun Look.small() = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp)
internal fun Look.label() = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight(500))
internal fun Look.caption() = TextStyle(fontFamily = font, fontSize = 12.sp, lineHeight = 16.sp)
internal fun Look.code() = TextStyle(fontFamily = monoFont, fontSize = 13.sp, lineHeight = 20.sp)
internal fun Look.eyebrow() =
    if (this == Look.Pixel) Type.pixel
    else TextStyle(fontFamily = font, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight(600), letterSpacing = 0.14.em)

internal fun r(dp: Dp) = RoundedCornerShape(dp)

// ---------------------------------------------------------------- chat

@Composable
fun ReplyScreen(look: Look) = Phone {
    val haze = rememberHazeState()
    val glassy = look == Look.Glass
    Box(Modifier.fillMaxSize().hazeSource(haze)) {
        if (glassy) DitherGlow(S.signal, Modifier.fillMaxWidth().height(260.dp), center = Offset(0.5f, -0.2f), radius = 0.7f, strength = 0.55f, falloff = 2.4f, cell = 2.dp, squash = 1.6f)
        Column(
            Modifier.fillMaxSize().padding(top = if (glassy) 84.dp else 132.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (look == Look.Cards) 14.dp else 12.dp),
        ) {
            UserMessage(look, "The nightly backup to the NAS failed again. Can you find out why and fix it?")
            if (look == Look.Cards) {
                ReplyCard(look)
            } else {
                if (look == Look.Glass) Byline(look)
                Tools(look)
                ReplyBody(look)
                ReplyFooter(look)
            }
        }
    }
    TopBar(look, haze, Modifier.align(Alignment.TopCenter))
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)) { ComposerFor(look, haze) }
}

@Composable
internal fun TopBar(look: Look, haze: HazeState, modifier: Modifier, title: String = "Fix the nightly backup", underline: Dp = 186.dp) {
    when (look) {
        Look.Current, Look.Pixel -> Column(modifier.fillMaxWidth().background(S.bg)) {
            Spacer(Modifier.height(40.dp))
            Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Lucide.PanelLeft, S.text2, 22.dp) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (look == Look.Pixel) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PixelSpinner(S.signal, 10.dp, phase = 3)
                            T(title.uppercase(), TextStyle(fontFamily = Roboto, fontWeight = FontWeight(700), fontSize = 14.sp, letterSpacing = 0.12.em))
                        }
                        Spacer(Modifier.height(6.dp))
                        T("HERMES · SONNET 5.5 · 18.4K CTX", Type.pixel.copy(fontSize = 9.5.sp), S.text3)
                    } else {
                        T(title.uppercase(), TextStyle(fontFamily = Roboto, fontWeight = FontWeight(700), fontSize = 15.sp, letterSpacing = 0.12.em))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.width(underline).height(2.dp).background(S.signal))
                    }
                }
                Row(
                    Modifier.clip(r(4.dp)).border(1.dp, S.line2, r(4.dp)).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Lucide.SquarePen, S.text2, 21.dp) }
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(Lucide.EllipsisVertical, S.text2, 20.dp) }
                }
            }
            if (look == Look.Pixel) Segments(5, 5, S.signal.copy(alpha = 0.0f), Modifier.fillMaxWidth().height(1.dp)) // spacer row
            Box(Modifier.fillMaxWidth().height(1.dp).background(S.line))
        }
        else -> {
            val glass = look == Look.Glass
            Column(
                modifier.fillMaxWidth().then(if (glass) Modifier.glass(haze, r(0.dp), S.bg.copy(alpha = 0.55f)) else Modifier.background(S.bg)),
            ) {
                Spacer(Modifier.height(40.dp))
                Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(Lucide.PanelLeft, look)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        T(title, TextStyle(fontFamily = look.font, fontWeight = FontWeight(600), fontSize = 16.sp, letterSpacing = (-0.1).sp))
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Dot(S.ok, 5.dp)
                            T("hermes · Sonnet 5.5", look.caption(), S.text3)
                        }
                    }
                    RoundIcon(Lucide.SquarePen, look)
                }
                if (!glass) Box(Modifier.fillMaxWidth().height(1.dp).background(S.line))
            }
        }
    }
}

@Composable
internal fun RoundIcon(icon: ImageVector, look: Look) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(if (look == Look.Glass) S.s2.copy(alpha = 0.6f) else S.s1), contentAlignment = Alignment.Center) {
        Icon(icon, S.text2, 20.dp)
    }
}

@Composable
internal fun UserMessage(look: Look, text: String) {
    when (look) {
        Look.Current, Look.Pixel -> Box(
            Modifier.fillMaxWidth().clip(r(4.dp)).background(Bubble).border(1.dp, if (look == Look.Pixel) S.signal.copy(alpha = 0.35f) else BubbleStroke, r(4.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (look == Look.Pixel) {
                Column {
                    T("YOU", Type.pixel.copy(fontSize = 9.5.sp), S.signal)
                    Spacer(Modifier.height(6.dp))
                    T(text, look.body())
                }
            } else T(text, look.body())
        }
        else -> Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f).widthIn(min = 56.dp))
            val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
            Box(Modifier.clip(shape).background(S.signalSoft).border(1.dp, BubbleStroke, shape).padding(horizontal = 16.dp, vertical = 11.dp)) {
                T(text, look.body())
            }
        }
    }
}

@Composable
internal fun Byline(look: Look) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentFace(Agent.Hermes, 24.dp)
        T("hermes", look.label())
        T("· 2m", look.caption(), S.text3)
    }
}

@Composable
internal fun Tools(look: Look) {
    when (look) {
        Look.Current -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Lucide.Brain, S.text3, 18.dp); Spacer(Modifier.width(12.dp)); T("Reasoning", look.body(), S.text2); Spacer(Modifier.width(10.dp)); Icon(Lucide.ChevronRight, S.text3, 16.dp) }
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Lucide.Wrench, S.text3, 18.dp); Spacer(Modifier.width(12.dp)); T("Used terminal and patch", look.body(), S.text2); Spacer(Modifier.width(10.dp)); Icon(Lucide.ChevronRight, S.text3, 16.dp) }
        }
        Look.Soft, Look.Glass -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolChip(look, Lucide.Brain, "Thought 8s")
            ToolChip(look, Lucide.Terminal, "terminal · patch", count = "4")
        }
        Look.Pixel -> Column(
            Modifier.fillMaxWidth().clip(r(4.dp)).background(S.s1).border(1.dp, S.line, r(4.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checker(S.ok, 7.dp); Spacer(Modifier.width(8.dp)); T("4 STEPS · 6.1S", Type.pixel.copy(fontSize = 9.5.sp), S.text3)
                Grow(); Segments(4, 4, S.ok, Modifier.width(64.dp), live = false, height = 4.dp)
            }
            Spacer(Modifier.height(2.dp))
            LogLine(look, "think", "why did rsync stop?", "8.0s")
            LogLine(look, "terminal", "tail backup.log", "0.4s")
            LogLine(look, "terminal", "df -h /mnt/nas", "1.2s", alert = "100%")
            LogLine(look, "patch", "backup.sh +3 −1", "0.2s")
        }
        Look.Cards -> {}
    }
}

@Composable
internal fun ToolChip(look: Look, icon: ImageVector, text: String, count: String? = null) {
    Row(
        Modifier.clip(CircleShape).background(if (look == Look.Glass) S.s1.copy(alpha = 0.8f) else S.s1).border(1.dp, S.line, CircleShape).padding(start = 11.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, S.text3, 15.dp)
        T(text, look.small(), S.text2)
        if (count != null) Box(Modifier.clip(CircleShape).background(S.s3).padding(horizontal = 6.dp, vertical = 1.dp)) { T(count, look.caption(), S.text2) }
        Icon(Lucide.ChevronDown, S.text3, 14.dp)
    }
}

@Composable
internal fun LogLine(look: Look, tool: String, arg: String, time: String, alert: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T("›", look.code(), S.ok, modifier = Modifier.width(14.dp))
        T(tool, look.code().copy(fontSize = 12.sp), S.text3, modifier = Modifier.width(72.dp))
        T(arg, look.code().copy(fontSize = 12.sp), S.text, maxLines = 1, modifier = Modifier.weight(1f))
        if (alert != null) { T(alert, look.code().copy(fontSize = 12.sp), S.bad); Spacer(Modifier.width(8.dp)) }
        T(time, look.code().copy(fontSize = 11.sp), S.text4)
    }
}

internal fun reply(look: Look, codeBg: Color, strong: Color) = listOf(
    buildAnnotatedString {
        append("The share on the NAS is ")
        withStyle(SpanStyle(fontWeight = FontWeight(700), color = strong)) { append("full") }
        append(": rsync stopped with ")
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("No space left on device") }
        append(". Every night's snapshot has been kept since March, so the 2 TB filled up.")
    },
    buildAnnotatedString {
        append("What I changed in ")
        withStyle(SpanStyle(fontFamily = look.monoFont, fontSize = 14.sp, background = codeBg)) { append(" backup.sh ") }
        append(":")
    },
)

@Composable
internal fun ReplyBody(look: Look) {
    val paragraphs = reply(look, S.s2, S.text)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T(paragraphs[0], look.body(), S.text)
        T(paragraphs[1], look.body(), S.text)
        Bullet(look, buildAnnotatedString { append("It now keeps the "); withStyle(SpanStyle(fontWeight = FontWeight(700))) { append("last 14 snapshots") }; append(" and removes older ones after each run.") })
        Bullet(look, AnnotatedString("Nothing else changes: same source, same schedule."))
        CodeBlock(look)
        T("Tonight's run will free about 1.3 TB. Want me to run it now instead?", look.body(), S.text)
    }
}

@Composable
internal fun Bullet(look: Look, text: AnnotatedString) {
    Row {
        if (look == Look.Pixel) Box(Modifier.padding(top = 9.dp, end = 10.dp).size(5.dp).background(S.signal))
        else if (look.rounded) Box(Modifier.padding(top = 9.dp, end = 10.dp).size(5.dp).clip(CircleShape).background(S.text3))
        else T("•", look.body(), S.text, modifier = Modifier.width(16.dp))
        T(text, look.body(), S.text)
    }
}

@Composable
internal fun CodeBlock(look: Look) {
    val shape = r(if (look.rounded) 14.dp else 4.dp)
    val code = buildAnnotatedString {
        if (look == Look.Current) append("ls -1d \"\$DEST\"/20* | head -n -14 | xargs -r rm -rf")
        else {
            withStyle(SpanStyle(color = Color(0xFFFF7B72))) { append("ls") }
            append(" -1d ")
            withStyle(SpanStyle(color = Color(0xFFA5D6FF))) { append("\"\$DEST\"/20*") }
            append(" | ")
            withStyle(SpanStyle(color = Color(0xFFFF7B72))) { append("head") }
            append(" -n -14 | ")
            withStyle(SpanStyle(color = Color(0xFFFF7B72))) { append("xargs") }
            append(" -r rm -rf")
        }
    }
    when (look) {
        Look.Current -> Column(Modifier.fillMaxWidth().clip(shape).background(S.s1).border(1.dp, S.line, shape).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row { T("bash", look.small(), S.text3); Grow(); Icon(Lucide.Copy, S.text3, 18.dp) }
            Spacer(Modifier.height(4.dp))
            T(code, look.code().copy(fontSize = 13.5.sp), S.text, maxLines = 1)
        }
        else -> Column(Modifier.fillMaxWidth().clip(shape).background(if (look == Look.Pixel) S.void.copy(alpha = 0.6f) else S.s1).border(1.dp, S.line, shape)) {
            Row(Modifier.fillMaxWidth().background(if (look == Look.Pixel) S.s1 else S.s2.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (look == Look.Pixel) T("BASH", Type.pixel.copy(fontSize = 9.5.sp), S.text3) else T("bash", look.caption().copy(fontFamily = look.monoFont), S.text3)
                Grow()
                Icon(Lucide.Copy, S.text3, 15.dp)
                if (look != Look.Pixel) { Spacer(Modifier.width(5.dp)); T("Copy", look.caption(), S.text3) }
            }
            T(code, look.code(), S.text, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        }
    }
}

@Composable
internal fun ReplyFooter(look: Look) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (look == Look.Current) 32.dp else 6.dp)) {
        if (look == Look.Current) {
            Icon(Lucide.Copy, S.text3, 20.dp); Icon(Lucide.GitBranch, S.text3, 20.dp); T("18.4k in · 612 out", look.small(), S.text3)
        } else if (look == Look.Pixel) {
            Icon(Lucide.Copy, S.text3, 18.dp); Spacer(Modifier.width(14.dp)); Icon(Lucide.GitBranch, S.text3, 18.dp); Spacer(Modifier.width(14.dp))
            T("18.4K IN · 612 OUT · $0.06", Type.pixel.copy(fontSize = 9.5.sp), S.text4)
        } else {
            FooterChip(Lucide.Copy, null); FooterChip(Lucide.GitBranch, null)
            Spacer(Modifier.width(6.dp))
            T("18.4k in · 612 out", look.caption(), S.text3)
        }
    }
}

@Composable
internal fun FooterChip(icon: ImageVector, text: String?) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(S.s1), contentAlignment = Alignment.Center) { Icon(icon, S.text3, 16.dp) }
}

@Composable
internal fun ReplyCard(look: Look) {
    val shape = r(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(S.s1).border(1.dp, S.line, shape)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentFace(Agent.Hermes, 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T("hermes", look.label())
                T("Sonnet 5.5 · 2m ago", look.caption(), S.text3)
            }
            Box(Modifier.clip(CircleShape).background(S.ok.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 4.dp)) { T("Done", look.caption().copy(fontWeight = FontWeight(500)), S.ok) }
        }
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth().clip(r(12.dp)).background(S.s2).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Brain, S.text3, 15.dp); Spacer(Modifier.width(6.dp)); T("Thought 8s", look.small(), S.text2)
            T("  ·  ", look.small(), S.text4)
            Icon(Lucide.Terminal, S.text3, 15.dp); Spacer(Modifier.width(6.dp)); T("4 tool calls", look.small(), S.text2)
            Grow(); Icon(Lucide.ChevronDown, S.text3, 16.dp)
        }
        val paragraphs = reply(look, S.s3, S.text)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T(paragraphs[0], look.body(), S.text)
            T(paragraphs[1], look.body(), S.text)
            Bullet(look, buildAnnotatedString { append("It now keeps the "); withStyle(SpanStyle(fontWeight = FontWeight(700))) { append("last 14 snapshots") }; append(" and removes older ones after each run.") })
            CodeBlock(look)
            T("Want me to run it now instead?", look.body(), S.text)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(S.line))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Icon(Lucide.Copy, S.text3, 17.dp) }
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Icon(Lucide.GitBranch, S.text3, 17.dp) }
            Grow()
            T("18.4k in · 612 out", look.caption().copy(fontFamily = look.monoFont), S.text4)
            Spacer(Modifier.width(10.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SuggestChip(look, "Run it now")
        SuggestChip(look, "Keep 30 instead")
    }
}

@Composable
internal fun SuggestChip(look: Look, text: String) {
    Box(Modifier.clip(CircleShape).border(1.dp, S.line2, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp)) { T(text, look.small(), S.text2) }
}

@Composable
internal fun ComposerFor(look: Look, haze: HazeState, placeholder: String = "Send a follow-up", running: Boolean = false) {
    val shape: Shape = when (look) { Look.Current, Look.Pixel -> r(6.dp); else -> r(26.dp) }
    val mod = if (look == Look.Glass) Modifier.glass(haze, shape, S.s2.copy(alpha = 0.6f))
    else Modifier.clip(shape).background(S.s1).border(1.dp, if (look == Look.Pixel) S.line2 else S.line2, shape)
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().then(mod).padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
        T(if (look == Look.Pixel) "› $placeholder" else placeholder, if (look == Look.Pixel) look.body().copy(fontFamily = RobotoMono, fontSize = 15.sp) else look.body(), S.text3)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Plus, S.text2, 22.dp); Spacer(Modifier.width(22.dp))
            Icon(Lucide.Mic, S.text2, 21.dp); Spacer(Modifier.width(22.dp))
            Icon(Lucide.AudioLines, S.text2, 21.dp)
            Grow()
            when (look) {
                Look.Current -> {
                    T("Sonnet 5.5", look.small().copy(fontSize = 15.sp), S.text2); Icon(Lucide.ChevronDown, S.text3, 14.dp); Spacer(Modifier.width(14.dp))
                    T("Medium", look.small().copy(fontSize = 15.sp), S.text2); Icon(Lucide.ChevronDown, S.text3, 14.dp)
                }
                Look.Pixel -> Box(Modifier.clip(r(3.dp)).border(1.dp, S.line2, r(3.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    T("SONNET 5.5 · MED", Type.pixel.copy(fontSize = 9.5.sp), S.text2)
                }
                else -> Row(
                    Modifier.clip(CircleShape).background(S.s3.copy(alpha = 0.8f)).padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    T("Sonnet 5.5", look.small(), S.text2); T("·", look.small(), S.text4); T("Medium", look.small(), S.text2)
                    Icon(Lucide.ChevronDown, S.text3, 14.dp)
                }
            }
            Spacer(Modifier.width(10.dp))
            val sendShape: Shape = if (look == Look.Pixel) r(4.dp) else CircleShape
            if (running) {
                Box(Modifier.size(44.dp).clip(sendShape).background(S.text), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(13.dp).clip(r(if (look == Look.Pixel) 1.dp else 3.dp)).border(2.dp, S.bg, r(if (look == Look.Pixel) 1.dp else 3.dp)))
                }
            } else Box(Modifier.size(44.dp).clip(sendShape).background(if (look == Look.Current) S.s3 else S.signal), contentAlignment = Alignment.Center) {
                Icon(Lucide.ArrowUp, if (look == Look.Current) S.text3 else S.onSignal, 20.dp)
            }
        }
    }
}

// ---------------------------------------------------------------- sidebar

internal data class Session(val title: String, val status: String?, val statusColor: Color?, val time: String, val agent: Agent, val draft: Boolean = false, val preview: String = "")

@Composable
internal fun sessions() = listOf(
    Session("Fix the nightly backup", "Running", S.ok, "2m", Agent.Hermes, preview = "Patching backup.sh"),
    Session("Audit the home server", "Needs approval", S.ember, "18m", Agent.Warden, preview = "sudo apt full-upgrade -y"),
    Session("Summarize the router changelog", "New reply", S.signal, "2h", Agent.Hermes, preview = "Three changes matter for you…"),
    Session("Plan a weekend in Vienna", "Has a question", S.ember, "5h", Agent.Scout, preview = "Train or flight from Munich?"),
    Session("Draft a reply to the landlord", null, null, "9h", Agent.Hermes, draft = true, preview = "Hi Marta, about the heating…"),
    Session("Rename photos by date taken", null, null, "1d", Agent.Ledger, preview = "Renamed 1,284 files"),
    Session("Compare two NAS drives", null, null, "2d", Agent.Scout, preview = "WD Red Plus is quieter by 5 dB"),
)

@Composable
fun SidebarScreen(look: Look) = Phone(background = S.bg) {
    val haze = rememberHazeState()
    val behind = rememberHazeState()
    val glass = look == Look.Glass
    // The chat, behind the open drawer.
    Box(Modifier.fillMaxSize().hazeSource(behind)) { ReplyScreen(look) }
    Box(Modifier.fillMaxSize().background(S.void.copy(alpha = if (glass) 0.25f else 0.55f)))
    val drawerShape: Shape = when {
        glass -> r(30.dp)
        look.rounded -> RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
        else -> r(0.dp)
    }
    val drawerMod = if (glass) Modifier.padding(start = 8.dp, top = 44.dp, bottom = 10.dp).glass(behind, drawerShape, Sidebar.copy(alpha = 0.62f))
    else Modifier.clip(drawerShape).background(Sidebar).border(1.dp, S.line, drawerShape)
    Box(Modifier.fillMaxHeight().width(338.dp).then(drawerMod)) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            if (glass) DitherGlow(S.signal, Modifier.fillMaxWidth().height(240.dp), center = Offset(0f, 0f), radius = 0.8f, strength = 0.5f, falloff = 2.6f, cell = 2.dp)
            Column(Modifier.fillMaxSize().padding(top = if (glass) 0.dp else 40.dp)) {
                SidebarHeader(look)
                Spacer(Modifier.height(if (look == Look.Current) 10.dp else 6.dp))
                NavItems(look)
                Spacer(Modifier.height(if (look == Look.Current) 24.dp else 18.dp))
                if (look == Look.Cards) CardsSessions(look) else ListSessions(look)
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = if (glass) 10.dp else 22.dp)) { SidebarFooter(look, haze) }
    }
}

@Composable
internal fun SidebarHeader(look: Look) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (look) {
            Look.Current -> T("HERALD", TextStyle(fontFamily = Roboto, fontWeight = FontWeight(900), fontSize = 26.sp, letterSpacing = 0.08.em))
            Look.Pixel -> PixelWordmark("HERALD", S.text, cell = 3.6.dp)
            else -> {
                AgentFace(Agent.Hermes, 32.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    T("Herald", TextStyle(fontFamily = look.font, fontWeight = FontWeight(700), fontSize = 20.sp, letterSpacing = (-0.3).sp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Dot(S.ok, 5.dp); T("homelab.tail", look.caption(), S.text3)
                    }
                }
            }
        }
        Grow()
        if (look.rounded) Box(Modifier.size(42.dp).clip(CircleShape).background(S.s1), contentAlignment = Alignment.Center) { Icon(Lucide.Search, S.text2, 19.dp) }
        else Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Lucide.Search, S.text2, 22.dp) }
    }
    if (look == Look.Pixel) Row(Modifier.padding(start = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(S.ok, 5.dp); Spacer(Modifier.width(6.dp)); T("HOMELAB.TAIL · 3 LIVE", Type.pixel.copy(fontSize = 9.5.sp), S.text3)
    }
}

@Composable
internal fun NavItems(look: Look) {
    val items = listOf(Lucide.CalendarClock to "Scheduled jobs", Lucide.ChartColumn to "Insights", Lucide.Layers to "Capabilities", Lucide.Archive to "Archived")
    if (look.rounded) {
        // Two by two tiles instead of four tall rows.
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (icon, label) ->
                        Row(
                            Modifier.weight(1f).clip(r(14.dp)).background(if (look == Look.Glass) S.s1.copy(alpha = 0.7f) else S.s1).border(1.dp, S.line, r(14.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, S.text2, 17.dp); Spacer(Modifier.width(9.dp)); T(label, look.label().copy(fontSize = 13.sp), S.text, maxLines = 1)
                        }
                    }
                }
            }
        }
    } else {
        Column {
            items.forEach { (icon, label) ->
                Row(Modifier.fillMaxWidth().height(if (look == Look.Pixel) 46.dp else 56.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, S.text2, if (look == Look.Pixel) 18.dp else 20.dp); Spacer(Modifier.width(18.dp))
                    T(label, look.body().copy(fontSize = if (look == Look.Pixel) 16.sp else 18.sp, fontWeight = FontWeight(500)))
                    if (look == Look.Pixel && label == "Scheduled jobs") { Grow(); T("2 TODAY", Type.pixel.copy(fontSize = 9.5.sp), S.text4) }
                }
            }
        }
    }
}

@Composable
internal fun SectionHead(look: Look, text: String, color: Color = S.signal, trailing: String? = null) {
    Row(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (look == Look.Current || look == Look.Pixel) { Checker(color, 9.dp); Spacer(Modifier.width(10.dp)) }
        T(text.uppercase(), look.eyebrow().let { if (look == Look.Current) it.copy(fontSize = 13.sp, letterSpacing = 0.16.em) else it }, if (look.rounded) S.text3 else color)
        if (trailing != null) { Grow(); T(trailing, look.caption(), S.text4) }
    }
}

@Composable
internal fun ListSessions(look: Look) {
    SectionHead(look, "Pinned")
    PinnedRow(look, "Home server runbook", "1d")
    PinnedRow(look, "Weekly meal plan", "3d")
    Spacer(Modifier.height(14.dp))
    SectionHead(look, "Sessions")
    Filters(look)
    Spacer(Modifier.height(8.dp))
    sessions().take(6).forEachIndexed { i, s -> SessionRow(look, s, selected = i == 0) }
}

@Composable
internal fun PinnedRow(look: Look, title: String, time: String) {
    Row(Modifier.fillMaxWidth().height(if (look.rounded) 42.dp else 48.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (look == Look.Glass) { Icon(Lucide.Pin, S.text3, 15.dp); Spacer(Modifier.width(14.dp)) }
        else if (look == Look.Soft) { Icon(Lucide.Pin, S.text3, 15.dp); Spacer(Modifier.width(14.dp)) }
        else { Dot(S.text4, 6.dp); Spacer(Modifier.width(18.dp)) }
        T(title, look.body().copy(fontSize = if (look == Look.Current) 17.sp else 16.sp), modifier = Modifier.weight(1f), maxLines = 1)
        if (look == Look.Current) { Icon(Lucide.Pin, S.text3, 17.dp); Spacer(Modifier.width(18.dp)) }
        T(time, look.small(), S.text3)
    }
}

@Composable
internal fun Filters(look: Look) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val items = listOf("All" to null, "Running" to "1", "Needs you" to "3")
        items.forEachIndexed { i, (label, n) ->
            val on = i == 0
            val shape: Shape = if (look.rounded) CircleShape else r(4.dp)
            Row(
                Modifier.clip(shape).background(if (on) S.signalSoft else Color.Transparent).border(1.dp, if (on) (if (look.rounded) S.signal.copy(alpha = 0.4f) else Color.Transparent) else S.line2, shape)
                    .padding(horizontal = 13.dp, vertical = if (look.rounded) 7.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (look == Look.Pixel) T(label.uppercase(), Type.pixel.copy(fontSize = 9.5.sp), if (on) S.signal else S.text2)
                else T(label, look.label().copy(fontSize = 15.sp), if (on) S.signal else S.text)
                if (n != null) {
                    if (look.rounded) Box(Modifier.clip(CircleShape).background(if (label == "Needs you") S.ember else S.ok).padding(horizontal = 6.dp)) { T(n, look.caption().copy(fontWeight = FontWeight(700)), S.onSignal) }
                    else T(if (look == Look.Pixel) n else "· $n", if (look == Look.Pixel) Type.pixel.copy(fontSize = 9.5.sp) else look.label().copy(fontSize = 15.sp), if (look == Look.Pixel) (if (label == "Running") S.ok else S.ember) else S.text)
                }
            }
        }
    }
}

@Composable
internal fun SessionRow(look: Look, s: Session, selected: Boolean) {
    val shape = r(if (look.rounded) 14.dp else 4.dp)
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(shape).background(if (selected) S.signalSoft else Color.Transparent).padding(horizontal = 12.dp, vertical = if (look == Look.Pixel) 8.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (look) {
            Look.Current -> { Dot(if (s.status == "Running") S.ok else S.text4, 6.dp); Spacer(Modifier.width(18.dp)) }
            Look.Pixel -> {
                if (s.status == "Running") PixelSpinner(S.ok, 10.dp, phase = 2) else Checker(s.statusColor ?: S.text4, 8.dp)
                Spacer(Modifier.width(14.dp))
            }
            else -> { AgentFace(s.agent, 30.dp); Spacer(Modifier.width(12.dp)) }
        }
        Column(Modifier.weight(1f)) {
            T(s.title, look.body().copy(fontSize = if (look == Look.Current) 17.sp else 15.sp, fontWeight = if (look.rounded) FontWeight(500) else FontWeight(400)), maxLines = 1)
            when {
                s.status != null && look == Look.Pixel -> {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(s.status.uppercase(), Type.pixel.copy(fontSize = 9.5.sp), s.statusColor!!)
                        if (s.status == "Running") { Spacer(Modifier.width(8.dp)); Segments(5, 3, S.ok, Modifier.width(54.dp), height = 3.dp) }
                    }
                }
                s.status != null && look.rounded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Dot(s.statusColor!!, 5.dp); T(s.status, look.caption().copy(fontSize = 13.sp, fontWeight = FontWeight(500)), s.statusColor)
                }
                s.status != null -> T(s.status, look.small().copy(fontSize = 15.sp), s.statusColor!!)
                s.draft && look.rounded -> T("Draft", look.caption().copy(fontSize = 13.sp), S.signal)
            }
        }
        if (s.draft && look == Look.Current) { T("Draft", look.small().copy(fontSize = 15.sp), S.signal); Spacer(Modifier.width(14.dp)) }
        if (s.draft && look == Look.Pixel) { T("DRAFT", Type.pixel.copy(fontSize = 9.5.sp), S.signal); Spacer(Modifier.width(10.dp)) }
        T(s.time, if (look == Look.Pixel) look.code().copy(fontSize = 12.sp) else look.small(), S.text3, modifier = Modifier.align(if (look == Look.Current || look == Look.Pixel) Alignment.Top else Alignment.CenterVertically))
    }
}

@Composable
internal fun CardsSessions(look: Look) {
    val list = sessions()
    SectionHead(look, "Needs you", S.ember, trailing = "2")
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionCard(look, list[1], highlight = true)
        SessionCard(look, list[3])
    }
    Spacer(Modifier.height(18.dp))
    SectionHead(look, "Today", trailing = "Running 1")
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionCard(look, list[0], selected = true)
        SessionCard(look, list[2])
        SessionCard(look, list[4])
    }
}

@Composable
internal fun SessionCard(look: Look, s: Session, selected: Boolean = false, highlight: Boolean = false) {
    val shape = r(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) S.signalSoft else S.s1)
            .border(1.dp, if (selected) S.signal.copy(alpha = 0.35f) else if (highlight) S.ember.copy(alpha = 0.35f) else S.line, shape).padding(12.dp),
    ) {
        AgentFace(s.agent, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(s.title, look.label().copy(fontSize = 15.sp), maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                T(s.time, look.caption(), S.text3)
            }
            Spacer(Modifier.height(2.dp))
            T(s.preview, look.small().copy(fontSize = 13.sp), S.text3, maxLines = 1)
            if (s.status != null || s.draft) {
                Spacer(Modifier.height(7.dp))
                val c = s.statusColor ?: S.signal
                Row(Modifier.clip(CircleShape).background(c.copy(alpha = 0.13f)).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (s.status == "Running") PixelSpinner(c, 8.dp, phase = 2) else Dot(c, 5.dp)
                    T(s.status ?: "Draft", look.caption().copy(fontWeight = FontWeight(500)), c)
                }
            }
        }
    }
}

@Composable
internal fun SidebarFooter(look: Look, haze: HazeState) {
    when (look) {
        Look.Current -> Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(S.line))
            Row(Modifier.fillMaxWidth().background(Sidebar).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(r(4.dp)).background(S.signal).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.SquarePen, S.onSignal, 20.dp); Spacer(Modifier.width(10.dp)); T("New session", look.label().copy(fontSize = 17.sp), S.onSignal)
                }
                Grow()
                Box(Modifier.size(52.dp).clip(CircleShape).background(S.signal), contentAlignment = Alignment.Center) { T("AM", look.label().copy(fontSize = 17.sp), S.onSignal) }
            }
        }
        Look.Pixel -> Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(S.line))
            Row(Modifier.fillMaxWidth().background(Sidebar).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(r(4.dp)).background(S.signal).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.SquarePen, S.onSignal, 18.dp); Spacer(Modifier.width(10.dp)); T("NEW SESSION", Type.pixel.copy(fontSize = 10.sp), S.onSignal)
                    Grow(); T("⌘N", look.code().copy(fontSize = 12.sp), S.onSignal.copy(alpha = 0.6f))
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(46.dp).clip(r(4.dp)).background(S.s2).border(1.dp, S.line2, r(4.dp)), contentAlignment = Alignment.Center) { T("AM", look.label(), S.text) }
            }
        }
        else -> Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            val mod = if (look == Look.Glass) Modifier.glass(haze, CircleShape, S.s2.copy(alpha = 0.55f)) else Modifier
            Row(Modifier.weight(1f).then(mod).padding(if (look == Look.Glass) 6.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(CircleShape).background(S.signal).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Lucide.SquarePen, S.onSignal, 18.dp); Spacer(Modifier.width(9.dp)); T("New chat", look.label().copy(fontSize = 16.sp, fontWeight = FontWeight(600)), S.onSignal)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(50.dp).clip(CircleShape).background(S.s3), contentAlignment = Alignment.Center) { T("AM", look.label(), S.text) }
            }
        }
    }
}

/** The wordmark spelled in checker pixels (5×7 glyphs). */
@Composable
fun PixelWordmark(word: String, color: Color, cell: Dp) {
    val glyphs = mapOf(
        'H' to listOf("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
        'E' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
        'R' to listOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
        'A' to listOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
        'L' to listOf("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
        'D' to listOf("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    )
    val accent = S.signal
    androidx.compose.foundation.Canvas(Modifier.width(cell * (word.length * 6 - 1)).height(cell * 7)) {
        val c = cell.toPx()
        word.forEachIndexed { gi, ch ->
            glyphs[ch]?.forEachIndexed { y, row ->
                row.forEachIndexed { x, b ->
                    if (b == '1') {
                        val X = gi * 6 + x
                        val tint = if (gi == 0 && (x + y) % 2 == 0) accent else color
                        drawRect(tint, Offset(X * c, y * c), androidx.compose.ui.geometry.Size(c * 0.84f, c * 0.84f), alpha = if ((X + y) % 2 == 0) 1f else 0.82f)
                    }
                }
            }
        }
    }
}

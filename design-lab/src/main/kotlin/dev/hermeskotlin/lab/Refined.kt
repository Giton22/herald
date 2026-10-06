package dev.hermeskotlin.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Wrench

/*
 * "Refined": today's Herald, kept recognisable (Roboto, spaced capitals, Nous blue, checker marks),
 * with tidier components and a few layout moves. Corners go from 2–6dp to 8–14dp, nothing rounder.
 */

private val L = Look.Current
private val cardR = RoundedCornerShape(12.dp)
private val btnR = RoundedCornerShape(10.dp)
private val codeR = RoundedCornerShape(10.dp)

// ---------------------------------------------------------------- shared chrome

@Composable
private fun RTopBar(title: String, subtitle: String?, live: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(S.bg)) {
        Spacer(Modifier.height(40.dp))
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Lucide.PanelLeft, S.text2, 22.dp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                T(title.uppercase(), TextStyle(fontFamily = Roboto, fontWeight = FontWeight(700), fontSize = 13.sp, letterSpacing = 0.14.em), maxLines = 1)
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (live) PixelSpinner(S.signal, 9.dp, phase = 3) else Dot(S.ok, 5.dp)
                        T(subtitle, L.caption(), S.text3)
                    }
                }
            }
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Lucide.SquarePen, S.text2, 20.dp) }
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Lucide.EllipsisVertical, S.text2, 20.dp) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(S.line))
    }
}

@Composable
private fun RUser(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f).widthIn(min = 44.dp))
        Box(Modifier.clip(cardR).background(Bubble).border(1.dp, BubbleStroke, cardR).padding(horizontal = 14.dp, vertical = 10.dp)) {
            T(text, L.body())
        }
    }
}

/** Reasoning and tools on one quiet line instead of two. */
@Composable
private fun RActivity(tools: String, time: String) {
    Row(
        Modifier.clip(btnR).background(S.s1).border(1.dp, S.line, btnR).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Brain, S.text3, 15.dp); Spacer(Modifier.width(6.dp)); T("Reasoning", L.small(), S.text2)
        T("   ·   ", L.small(), S.text4)
        Icon(Lucide.Wrench, S.text3, 15.dp); Spacer(Modifier.width(6.dp)); T(tools, L.small(), S.text2)
        Spacer(Modifier.width(8.dp)); T(time, L.caption(), S.text3)
        Spacer(Modifier.width(6.dp)); Icon(Lucide.ChevronRight, S.text3, 15.dp)
    }
}

@Composable
private fun RCode(code: AnnotatedString, lang: String = "bash") {
    Column(Modifier.fillMaxWidth().clip(codeR).background(S.s1).border(1.dp, S.line, codeR)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            T(lang, L.caption().copy(fontFamily = RobotoMono), S.text3)
            Grow()
            Icon(Lucide.Copy, S.text3, 15.dp)
        }
        T(code, L.code(), S.text, maxLines = 1, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp))
    }
}

private val pruneCode = buildAnnotatedString {
    withStyle(SpanStyle(color = Color(0xFFFF7B72))) { append("ls") }
    append(" -1d ")
    withStyle(SpanStyle(color = Color(0xFFA5D6FF))) { append("\"\$DEST\"/20*") }
    append(" | ")
    withStyle(SpanStyle(color = Color(0xFFFF7B72))) { append("head") }
    append(" -n -14 | ")
    withStyle(SpanStyle(color = Color(0xFFFF7B72))) { append("xargs") }
    append(" -r rm -rf")
}

@Composable
private fun RBullet(text: AnnotatedString) {
    Row {
        Box(Modifier.padding(top = 10.dp, start = 2.dp, end = 12.dp).size(5.dp).background(S.signal))
        T(text, L.body())
    }
}

@Composable
private fun RComposer(placeholder: String, running: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(shape).background(S.s1).border(1.dp, S.line2, shape).padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
        T(placeholder, L.body(), S.text3)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Plus, S.text2, 22.dp); Spacer(Modifier.width(20.dp))
            Icon(Lucide.Mic, S.text2, 21.dp); Spacer(Modifier.width(20.dp))
            Icon(Lucide.AudioLines, S.text2, 21.dp)
            Grow()
            Row(
                Modifier.clip(btnR).border(1.dp, S.line2, btnR).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                T("Sonnet 5.5", L.small(), S.text2); T("·", L.small(), S.text4); T("Medium", L.small(), S.text2)
                Icon(Lucide.ChevronDown, S.text3, 14.dp)
            }
            Spacer(Modifier.width(10.dp))
            if (running) Box(Modifier.size(42.dp).clip(btnR).background(S.text), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(S.bg))
            } else Box(Modifier.size(42.dp).clip(btnR).background(S.signal), contentAlignment = Alignment.Center) {
                Icon(Lucide.ArrowUp, S.onSignal, 20.dp)
            }
        }
    }
}

// ---------------------------------------------------------------- reply

@Composable
fun RefinedReply() = Phone {
    Column(Modifier.fillMaxSize().padding(top = 124.dp, start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RUser("The nightly backup to the NAS failed again. Can you find out why and fix it?")
        RActivity("terminal, patch", "6s")
        val p = reply(L, S.s2, S.text)
        T(p[0], L.body())
        T(p[1], L.body())
        RBullet(buildAnnotatedString { append("It now keeps the "); withStyle(SpanStyle(fontWeight = FontWeight(700))) { append("last 14 snapshots") }; append(" and removes older ones after each run.") })
        RBullet(AnnotatedString("Nothing else changes: same source, same schedule."))
        RCode(pruneCode)
        T("Tonight's run will free about 1.3 TB. Want me to run it now instead?", L.body())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Copy, S.text3, 18.dp); Spacer(Modifier.width(22.dp))
            Icon(Lucide.GitBranch, S.text3, 18.dp); Spacer(Modifier.width(22.dp))
            T("18.4k in · 612 out · $0.06", L.caption(), S.text3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RChip("Run it now"); RChip("Keep 30 instead") }
    }
    Box(Modifier.align(Alignment.TopCenter)) { RTopBar("Fix the nightly backup", "Sonnet 5.5 · done 2m ago") }
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)) { RComposer("Send a follow-up") }
}

@Composable
private fun RChip(text: String) {
    Box(Modifier.clip(btnR).border(1.dp, S.line2, btnR).padding(horizontal = 12.dp, vertical = 8.dp)) { T(text, L.small(), S.text2) }
}

// ---------------------------------------------------------------- at work

@Composable
fun RefinedWorking() = Phone {
    Column(
        Modifier.fillMaxSize().padding(top = 124.dp, bottom = 250.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
    ) {
        RUser("The nightly backup to the NAS failed again. Can you find out why and fix it?")
        // Steps listed while the turn runs, instead of one "Used ..." line.
        Column(Modifier.fillMaxWidth().clip(cardR).background(S.s1).border(1.dp, S.line, cardR).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RStep(true, Lucide.Terminal, "Read the backup log", "found ENOSPC at 02:14", "0.4s")
            RStep(null, Lucide.Terminal, "Reading /opt/backup/backup.sh", null, "")
            RStep(false, Lucide.Terminal, "Check the disk, patch, run once", null, "")
        }
    }
    Box(Modifier.align(Alignment.TopCenter)) { RTopBar("Fix the nightly backup", "Sonnet 5.5 · working 24s", live = true) }
    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)) {
        // Status line: same place, now with progress and its own Stop.
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(cardR).background(S.signalSoft).border(1.dp, S.signal.copy(alpha = 0.3f), cardR).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelSpinner(S.signal, 12.dp, phase = 5); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T("Reading a file", L.label(), S.text)
                Spacer(Modifier.height(5.dp))
                Segments(5, 1, S.signal, Modifier.width(120.dp), height = 3.dp)
            }
            T("2/5", L.small(), S.text2); Spacer(Modifier.width(10.dp))
            Icon(Lucide.ChevronUp, S.text3, 16.dp)
        }
        Spacer(Modifier.height(8.dp))
        RComposer("Steer, or queue what's next", running = true)
    }
}

@Composable
private fun RStep(done: Boolean?, icon: ImageVector, text: String, detail: String?, time: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(18.dp), contentAlignment = Alignment.Center) {
            when (done) {
                true -> Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(S.ok.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(Lucide.Check, S.ok, 11.dp) }
                null -> PixelSpinner(S.signal, 12.dp, phase = 6)
                false -> Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).border(1.5.dp, S.text4, RoundedCornerShape(3.dp)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(text, L.small().copy(fontSize = 15.sp), when (done) { true -> S.text2; null -> S.text; false -> S.text3 })
            if (detail != null) T(detail, L.caption().copy(fontFamily = RobotoMono), S.text3)
        }
        T(time, L.caption(), S.text3)
    }
}

// ---------------------------------------------------------------- approval

@Composable
fun RefinedApproval() = Phone {
    Column(Modifier.fillMaxSize().padding(top = 124.dp, start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RCode(pruneCode)
        T("Tonight's run will free about 1.3 TB. Want me to run it now instead?", L.body())
        RUser("Yes, run it now.")
    }
    Box(Modifier.align(Alignment.TopCenter)) { RTopBar("Fix the nightly backup", "Sonnet 5.5 · waiting for you", live = false) }
    Column(
        Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 22.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(S.s1).border(1.dp, S.ember.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
    ) {
        // Header strip replaces the separate "Waiting for your answer" bar.
        Row(Modifier.fillMaxWidth().background(S.emberSoft).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checker(S.ember, 8.dp); Spacer(Modifier.width(10.dp))
            T("WAITING FOR YOUR ANSWER", L.eyebrow(), S.ember)
            Grow()
            Icon(Lucide.CircleStop, S.text2, 15.dp); Spacer(Modifier.width(6.dp)); T("Stop task", L.small(), S.text2)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.ShieldAlert, S.ember, 20.dp); Spacer(Modifier.width(10.dp))
                T("Allow this command?", L.body().copy(fontSize = 18.sp, fontWeight = FontWeight(600)))
            }
            T("Hermes wants to run this with terminal. Run the backup now so the old snapshots are cleared.", L.small().copy(fontSize = 15.sp), S.text2)
            Row(Modifier.fillMaxWidth().clip(codeR).background(S.bg).border(1.dp, S.line, codeR).padding(start = 12.dp, end = 10.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                T(buildAnnotatedString { withStyle(SpanStyle(color = S.ok)) { append("$ ") }; withStyle(SpanStyle(color = S.ember)) { append("sudo ") }; append("systemctl start backup.service") }, L.code().copy(fontSize = 13.5.sp), maxLines = 1, modifier = Modifier.weight(1f))
                Icon(Lucide.Copy, S.text3, 16.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).clip(btnR).border(1.dp, S.line2, btnR).padding(vertical = 13.dp), contentAlignment = Alignment.Center) { T("Deny", L.label().copy(fontSize = 15.sp)) }
                Box(Modifier.weight(1.4f).clip(btnR).background(S.signal).padding(vertical = 13.dp), contentAlignment = Alignment.Center) { T("Allow once", L.label().copy(fontSize = 15.sp), S.onSignal) }
            }
            // Broader permissions folded into one row; tap opens the two choices.
            Row(Modifier.fillMaxWidth().clip(btnR).background(S.s2).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("More ways to allow", L.label())
                    T("For this chat, or always", L.caption(), S.text3)
                }
                Icon(Lucide.ChevronDown, S.text3, 18.dp)
            }
        }
    }
}

// ---------------------------------------------------------------- new chat

@Composable
fun RefinedNewChat() = Phone {
    Column(
        Modifier.fillMaxSize().padding(top = 110.dp, bottom = 190.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        T("HERALD", TextStyle(fontFamily = Roboto, fontWeight = FontWeight(900), fontSize = 58.sp, letterSpacing = 0.08.em), Color(0xFFD1D7DE))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(S.ok, 6.dp); T("Connected to homelab.tail", L.small(), S.text3)
        }
        Spacer(Modifier.height(30.dp))
        // Starters as a short list under the wordmark; Attach and Dictate stay.
        Column(Modifier.fillMaxWidth().clip(cardR).background(S.s1).border(1.dp, S.line, cardR)) {
            Starter("Check free space on the NAS")
            Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(S.line))
            Starter("What ran overnight?")
            Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(S.line))
            Starter("Draft a reply to the landlord")
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RAction(Lucide.Paperclip, "Attach", Modifier.weight(1f))
            RAction(Lucide.Mic, "Dictate", Modifier.weight(1f))
        }
    }
    Box(Modifier.align(Alignment.TopCenter)) { RTopBar("New chat", "hermes · Sonnet 5.5") }
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)) { RComposer("What are we building?") }
}

@Composable
private fun Starter(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        T(text, L.body(), S.text, modifier = Modifier.weight(1f))
        Icon(Lucide.ArrowUp, S.text3, 16.dp)
    }
}

@Composable
private fun RAction(icon: ImageVector, text: String, modifier: Modifier) {
    Row(modifier.clip(btnR).border(1.dp, S.line2, btnR).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, S.text, 17.dp); Spacer(Modifier.width(8.dp)); T(text, L.label().copy(fontSize = 15.sp))
    }
}

// ---------------------------------------------------------------- sidebar

@Composable
fun RefinedSidebar() = Phone(background = S.bg) {
    Box(Modifier.fillMaxSize()) { RefinedReply() }
    Box(Modifier.fillMaxSize().background(S.void.copy(alpha = 0.55f)))
    val drawer = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
    Box(Modifier.fillMaxHeight().width(338.dp).clip(drawer).background(Sidebar).border(1.dp, S.line, drawer)) {
        Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("HERALD", TextStyle(fontFamily = Roboto, fontWeight = FontWeight(900), fontSize = 24.sp, letterSpacing = 0.08.em))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Dot(S.ok, 5.dp); T("homelab.tail", L.caption(), S.text3) }
                }
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Lucide.Search, S.text2, 21.dp) }
            }
            Spacer(Modifier.height(14.dp))
            // Four nav rows become one compact strip of icon buttons.
            Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavTile(Lucide.CalendarClock, "Jobs", Modifier.weight(1f))
                NavTile(Lucide.ChartColumn, "Insights", Modifier.weight(1f))
                NavTile(Lucide.Layers, "Skills", Modifier.weight(1f))
                NavTile(Lucide.Archive, "Archive", Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
            RSection("Pinned")
            RPinned("Home server runbook", "1d")
            RPinned("Weekly meal plan", "3d")
            Spacer(Modifier.height(16.dp))
            RSection("Sessions")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RFilter("All", null, true); RFilter("Running", "1", false, S.ok); RFilter("Needs you", "3", false, S.ember)
            }
            Spacer(Modifier.height(8.dp))
            sessions().take(6).forEachIndexed { i, s -> RSession(s, i == 0) }
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 14.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(btnR).background(S.signal).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Lucide.SquarePen, S.onSignal, 18.dp); Spacer(Modifier.width(9.dp)); T("New session", L.label().copy(fontSize = 16.sp), S.onSignal)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(48.dp).clip(CircleShape).background(S.s3), contentAlignment = Alignment.Center) { T("AM", L.label(), S.text) }
        }
    }
}

@Composable
private fun NavTile(icon: ImageVector, text: String, modifier: Modifier) {
    Column(modifier.clip(btnR).background(S.s1).border(1.dp, S.line, btnR).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, S.text2, 19.dp); Spacer(Modifier.height(5.dp)); T(text, L.caption(), S.text2)
    }
}

@Composable
private fun RSection(text: String) {
    Row(Modifier.padding(start = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checker(S.signal, 8.dp); Spacer(Modifier.width(9.dp)); T(text.uppercase(), L.eyebrow(), S.signal)
    }
}

@Composable
private fun RPinned(title: String, time: String) {
    Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.Pin, S.text3, 14.dp); Spacer(Modifier.width(14.dp))
        T(title, L.body(), modifier = Modifier.weight(1f), maxLines = 1)
        T(time, L.small(), S.text3)
    }
}

@Composable
private fun RFilter(text: String, n: String?, on: Boolean, nColor: Color = S.text) {
    Row(
        Modifier.clip(btnR).background(if (on) S.signalSoft else Color.Transparent).border(1.dp, if (on) S.signal.copy(alpha = 0.4f) else S.line2, btnR).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        T(text, L.label(), if (on) S.signal else S.text)
        if (n != null) T(n, L.label().copy(fontWeight = FontWeight(700)), nColor)
    }
}

@Composable
private fun RSession(s: Session, selected: Boolean) {
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(IntrinsicSize.Min).clip(btnR).background(if (selected) S.signalSoft else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(if (selected) S.signal else Color.Transparent))
        Row(Modifier.weight(1f).padding(start = 9.dp, end = 12.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(18.dp)) {
                if (s.status == "Running") PixelSpinner(S.ok, 9.dp, phase = 2) else Dot(s.statusColor ?: S.text4, 6.dp)
            }
            Column(Modifier.weight(1f)) {
                T(s.title, L.body().copy(fontSize = 15.5.sp, fontWeight = if (s.statusColor == S.signal) FontWeight(600) else FontWeight(400)), maxLines = 1)
                when {
                    s.status != null -> T(s.status, L.caption().copy(fontSize = 13.sp), s.statusColor!!)
                    s.draft -> T("Draft · " + s.preview, L.caption().copy(fontSize = 13.sp), S.text3, maxLines = 1)
                }
            }
            T(s.time, L.caption(), S.text3)
        }
    }
}

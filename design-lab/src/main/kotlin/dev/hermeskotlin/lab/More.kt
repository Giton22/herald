package dev.hermeskotlin.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Fingerprint
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mail
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.Wrench
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// ---------------------------------------------------------------- new chat

@Composable
fun NewChatScreen(look: Look) = Phone {
    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize().hazeSource(haze)) {
        Column(
            Modifier.fillMaxSize().padding(top = 112.dp, bottom = 190.dp, start = 18.dp, end = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (look) {
                Look.Current -> {
                    T("HERALD", TextStyle(fontFamily = Roboto, fontWeight = FontWeight(900), fontSize = 66.sp, letterSpacing = 0.08.em), Color(0xFFD1D7DE))
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.clip(CircleShape).border(1.dp, S.line2, CircleShape)) {
                        Row(Modifier.padding(horizontal = 30.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Lucide.Paperclip, S.text, 19.dp); Spacer(Modifier.width(10.dp)); T("Attach", look.label().copy(fontSize = 16.sp)) }
                        Box(Modifier.width(1.dp).height(50.dp).background(S.line2))
                        Row(Modifier.padding(horizontal = 30.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Lucide.Mic, S.text, 19.dp); Spacer(Modifier.width(10.dp)); T("Dictate", look.label().copy(fontSize = 16.sp)) }
                    }
                }
                Look.Pixel -> {
                    PixelWordmark("HERALD", S.text, cell = 9.dp)
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(S.ok, 6.dp); Spacer(Modifier.width(8.dp))
                        T("READY · HOMELAB.TAIL · 4 AGENTS", Type.pixel.copy(fontSize = 9.5.sp), S.text3)
                    }
                    Spacer(Modifier.height(28.dp))
                    Column(Modifier.fillMaxWidth().clip(r(4.dp)).background(S.s1).border(1.dp, S.line, r(4.dp)).padding(vertical = 6.dp)) {
                        Slash(look, "/disk", "check free space on the NAS")
                        Slash(look, "/jobs", "what ran overnight")
                        Slash(look, "@scout", "hand a question to an agent")
                        Slash(look, "/model", "Sonnet 5.5 · medium", last = true)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SquareAction(look, Lucide.Paperclip, "ATTACH")
                        SquareAction(look, Lucide.Mic, "DICTATE")
                    }
                }
                Look.Cards -> {
                    Column(Modifier.fillMaxWidth()) {
                        T("Good evening, Alex", TextStyle(fontFamily = Geist, fontWeight = FontWeight(600), fontSize = 30.sp, letterSpacing = (-0.8).sp))
                        Spacer(Modifier.height(4.dp))
                        T("2 chats need you · 1 running", look.body(), S.text3)
                        Spacer(Modifier.height(20.dp))
                        SessionCard(look, sessions()[1], highlight = true)
                        Spacer(Modifier.height(8.dp))
                        SessionCard(look, sessions()[0])
                        Spacer(Modifier.height(22.dp))
                        T("START WITH", look.eyebrow(), S.text3)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SuggestChip(look, "Check the NAS"); SuggestChip(look, "Overnight jobs") }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SuggestChip(look, "Draft an email"); SuggestChip(look, "Ask scout") }
                    }
                }
                else -> {
                    val glass = look == Look.Glass
                    Box(contentAlignment = Alignment.Center) {
                        if (glass) DitherGlow(S.signal, Modifier.size(220.dp), radius = 0.5f, strength = 0.9f, falloff = 1.8f, cell = 2.dp)
                        AgentFace(Agent.Hermes, 64.dp)
                    }
                    Spacer(Modifier.height(if (glass) 0.dp else 20.dp))
                    T("What are we building?", TextStyle(fontFamily = look.font, fontWeight = FontWeight(600), fontSize = 26.sp, letterSpacing = (-0.5).sp))
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(S.ok, 6.dp); T("hermes on homelab.tail", look.small(), S.text3)
                    }
                    Spacer(Modifier.height(28.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Idea(look, haze, Lucide.HardDrive, "Check free space on the NAS", Modifier.weight(1f))
                            Idea(look, haze, Lucide.CalendarClock, "What ran overnight?", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Idea(look, haze, Lucide.Mail, "Draft a reply to the landlord", Modifier.weight(1f))
                            Idea(look, haze, Lucide.FileText, "Summarize a PDF", Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    TopBar(look, haze, Modifier.align(Alignment.TopCenter), title = "New chat", underline = 96.dp)
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)) { ComposerFor(look, haze, placeholder = "What are we building?") }
}

@Composable
private fun Slash(look: Look, cmd: String, what: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        T(cmd, look.code(), S.signal, modifier = Modifier.width(78.dp))
        T(what, look.code().copy(fontSize = 12.sp), S.text3, maxLines = 1)
    }
    if (!last) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(S.line))
}

@Composable
private fun SquareAction(look: Look, icon: ImageVector, text: String) {
    Row(Modifier.clip(r(4.dp)).border(1.dp, S.line2, r(4.dp)).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, S.text2, 16.dp); Spacer(Modifier.width(9.dp)); T(text, Type.pixel.copy(fontSize = 9.5.sp), S.text)
    }
}

@Composable
private fun Idea(look: Look, haze: HazeState, icon: ImageVector, text: String, modifier: Modifier) {
    val shape = r(18.dp)
    val bg = if (look == Look.Glass) Modifier.glass(haze, shape, S.s1.copy(alpha = 0.6f)) else Modifier.clip(shape).background(S.s1).border(1.dp, S.line, shape)
    Column(modifier.height(96.dp).then(bg).padding(14.dp)) {
        Icon(icon, S.signal, 19.dp)
        Spacer(Modifier.weight(1f))
        T(text, look.small(), S.text, maxLines = 2)
    }
}

// ---------------------------------------------------------------- a turn at work

@Composable
fun WorkingScreen(look: Look) = Phone {
    val haze = rememberHazeState()
    val composerSpace = if (look == Look.Current) 252.dp else 214.dp
    Box(Modifier.fillMaxSize().hazeSource(haze)) {
        if (look == Look.Glass) DitherGlow(S.signal, Modifier.fillMaxSize(), center = Offset(0.5f, 0.86f), radius = 0.5f, strength = 0.35f, falloff = 2.8f, cell = 2.dp, squash = 2f)
        Column(
            Modifier.fillMaxSize().padding(top = 120.dp, bottom = composerSpace, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            UserMessage(look, "The nightly backup to the NAS failed again. Can you find out why and fix it?")
            when (look) {
                Look.Current -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Wrench, S.text3, 18.dp); Spacer(Modifier.width(12.dp)); T("Used terminal and read_file", look.body(), S.text2)
                }
                Look.Soft, Look.Glass -> {
                    if (look == Look.Glass) Byline(look)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolChip(look, Lucide.Wrench, "terminal · read_file", count = "2")
                    }
                    StreamingText(look)
                }
                Look.Pixel -> PixelLog()
                Look.Cards -> WorkingCard(look)
            }
        }
    }
    TopBar(look, haze, Modifier.align(Alignment.TopCenter))
    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LiveStatus(look, haze)
        Spacer(Modifier.height(if (look == Look.Current) 8.dp else 10.dp))
        ComposerFor(look, haze, running = true)
    }
}

@Composable
private fun StreamingText(look: Look) {
    Row {
        T(
            buildAnnotatedString {
                append("rsync stopped with ")
                withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append("No space left on device") }
                append(". Checking how the script prunes old snapshots")
            },
            look.body(), S.text, modifier = Modifier.weight(1f, fill = false),
        )
        Box(Modifier.align(Alignment.Bottom).padding(start = 3.dp, bottom = 3.dp).width(9.dp).height(17.dp).background(S.signal))
    }
}

@Composable
private fun PixelLog() {
    val look = Look.Pixel
    Column(
        Modifier.fillMaxWidth().clip(r(4.dp)).background(S.s1).border(1.dp, S.line, r(4.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelSpinner(S.signal, 9.dp, phase = 3); Spacer(Modifier.width(8.dp)); T("WORKING · STEP 2 OF 5", Type.pixel.copy(fontSize = 9.5.sp), S.signal)
            Grow(); Segments(5, 1, S.signal, Modifier.width(70.dp), height = 4.dp)
        }
        Spacer(Modifier.height(2.dp))
        LogLine(look, "terminal", "tail -n 40 backup.log", "0.4s", alert = "ENOSPC")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(14.dp)) { PixelSpinner(S.signal, 9.dp, phase = 6) }
            T("read_file", look.code().copy(fontSize = 12.sp), S.text3, modifier = Modifier.width(72.dp))
            T("/opt/backup/backup.sh", look.code().copy(fontSize = 12.sp), S.signalHi, maxLines = 1, modifier = Modifier.weight(1f))
            T("live", look.code().copy(fontSize = 11.sp), S.signal)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("·", look.code(), S.text4, modifier = Modifier.width(14.dp))
            T("next", look.code().copy(fontSize = 12.sp), S.text4, modifier = Modifier.width(72.dp))
            T("check disk, patch, run once", look.code().copy(fontSize = 12.sp), S.text4)
        }
    }
}

@Composable
private fun WorkingCard(look: Look) {
    val shape = r(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(S.s1).border(1.dp, S.signal.copy(alpha = 0.3f), shape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentFace(Agent.Hermes, 28.dp, glow = true); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { T("hermes", look.label()); T("Sonnet 5.5 · 24s", look.caption(), S.text3) }
            Row(Modifier.clip(CircleShape).background(S.signal.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PixelSpinner(S.signal, 8.dp, phase = 4); T("Working", look.caption().copy(fontWeight = FontWeight(500)), S.signal)
            }
        }
        Segments(5, 1, S.signal, Modifier.fillMaxWidth(), height = 4.dp)
        CardStep(look, done = true, text = "Read the backup log", detail = "found ENOSPC at 02:14")
        CardStep(look, done = false, text = "Reading backup.sh", detail = "/opt/backup/backup.sh")
        CardStep(look, done = null, text = "Check disk, patch, run once", detail = null)
    }
}

@Composable
private fun CardStep(look: Look, done: Boolean?, text: String, detail: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 3.dp).size(16.dp), contentAlignment = Alignment.Center) {
            when (done) {
                true -> Box(Modifier.size(16.dp).clip(CircleShape).background(S.ok.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(Lucide.Check, S.ok, 11.dp) }
                false -> PixelSpinner(S.signal, 12.dp, phase = 5)
                null -> Box(Modifier.size(12.dp).clip(CircleShape).border(1.5.dp, S.text4, CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            T(text, look.label(), if (done == null) S.text3 else S.text)
            if (detail != null) T(detail, look.caption().copy(fontFamily = look.monoFont), S.text3)
        }
    }
}

@Composable
private fun LiveStatus(look: Look, haze: HazeState) {
    when (look) {
        Look.Current -> Row(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(r(4.dp)).background(S.s1).border(1.dp, S.line2, r(4.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, S.signal.copy(alpha = 0.35f), CircleShape))
            Spacer(Modifier.width(12.dp)); T("Reading a file: /opt/backup/backup.sh", look.body(), S.text, maxLines = 1, modifier = Modifier.weight(1f))
            T("2/5", look.body(), S.text2); Spacer(Modifier.width(10.dp)); Icon(Lucide.ChevronUp, S.text3, 16.dp)
        }
        Look.Pixel -> Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(r(4.dp)).background(S.signalSoft).border(1.dp, S.signal.copy(alpha = 0.35f), r(4.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelSpinner(S.signal, 11.dp, phase = 2); Spacer(Modifier.width(10.dp))
            T("READING BACKUP.SH", Type.pixel.copy(fontSize = 10.sp), S.text, modifier = Modifier.weight(1f))
            T("00:24", look.code().copy(fontSize = 12.sp), S.text3); Spacer(Modifier.width(10.dp))
            Row(Modifier.clip(r(3.dp)).border(1.dp, S.line2, r(3.dp)).padding(horizontal = 7.dp, vertical = 4.dp)) { T("STOP", Type.pixel.copy(fontSize = 9.sp), S.bad) }
        }
        else -> {
            val mod = if (look == Look.Glass) Modifier.glass(haze, CircleShape, S.s3.copy(alpha = 0.6f)) else Modifier.clip(CircleShape).background(S.s2).border(1.dp, S.line2, CircleShape)
            Row(mod.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelSpinner(S.signal, 12.dp, phase = 6); Spacer(Modifier.width(9.dp))
                T("Reading backup.sh", look.label()); Spacer(Modifier.width(10.dp))
                Segments(5, 1, S.signal, Modifier.width(50.dp), height = 4.dp); Spacer(Modifier.width(8.dp))
                T("2/5", look.caption(), S.text3)
            }
        }
    }
}

// ---------------------------------------------------------------- an approval

@Composable
fun ApprovalScreen(look: Look) = Phone {
    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize().hazeSource(haze)) {
        Column(Modifier.fillMaxSize().padding(top = 128.dp, start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Bullet(look, buildAnnotatedString { append("It now keeps the "); withStyle(SpanStyle(fontWeight = FontWeight(700))) { append("last 14 snapshots") }; append(" and removes older ones after each run.") })
            CodeBlock(look)
            T("Tonight's run will free about 1.3 TB. Want me to run it now instead?", look.body())
            UserMessage(look, "Yes, run it now.")
        }
    }
    TopBar(look, haze, Modifier.align(Alignment.TopCenter))
    if (look == Look.Glass || look == Look.Cards) Box(Modifier.fillMaxSize().background(S.void.copy(alpha = 0.45f)))
    Box(Modifier.align(Alignment.BottomCenter)) { ApprovalPanel(look, haze) }
}

@Composable
private fun ApprovalPanel(look: Look, haze: HazeState) {
    val why = "Hermes wants to run this with terminal. Run the backup now so the old snapshots are cleared."
    when (look) {
        Look.Current -> Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clip(r(4.dp)).background(S.s1).border(1.dp, S.line2, r(4.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, S.signal.copy(alpha = 0.35f), CircleShape)); Spacer(Modifier.width(12.dp)); T("Waiting for your answer", look.body())
            }
            Column(Modifier.fillMaxWidth().clip(r(4.dp)).background(S.s1).border(1.dp, S.line2, r(4.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.ShieldAlert, S.ember, 20.dp); Spacer(Modifier.width(12.dp)); T("Allow this command?", look.body().copy(fontSize = 18.sp, fontWeight = FontWeight(600)), modifier = Modifier.weight(1f))
                    Icon(Lucide.CircleStop, S.text, 17.dp); Spacer(Modifier.width(8.dp)); T("Stop task", look.label().copy(fontSize = 15.sp))
                }
                T(why, look.small().copy(fontSize = 15.sp), S.text2)
                Box(Modifier.fillMaxWidth().clip(r(2.dp)).background(S.bg).border(1.dp, S.line, r(2.dp)).padding(12.dp)) { T("sudo systemctl start backup.service", look.code().copy(fontSize = 14.sp)) }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) { Icon(Lucide.Copy, S.text, 17.dp); Spacer(Modifier.width(8.dp)); T("Copy command", look.label().copy(fontSize = 15.sp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).clip(r(2.dp)).border(1.dp, S.line2, r(2.dp)).padding(vertical = 14.dp), contentAlignment = Alignment.Center) { T("Deny", look.label().copy(fontSize = 16.sp)) }
                    Box(Modifier.weight(1f).clip(r(2.dp)).background(S.signal).padding(vertical = 14.dp), contentAlignment = Alignment.Center) { T("Allow once", look.label().copy(fontSize = 16.sp), S.onSignal) }
                }
                T("Allow once runs only this command, this time.", look.small(), S.text3)
                T("Broader permissions", look.small(), S.text3)
                Column(Modifier.fillMaxWidth().clip(r(2.dp)).background(S.s2).padding(12.dp)) {
                    T("Allow for this chat", look.body()); T("Commands like this run without asking again, in this chat only, until it ends.", look.small().copy(fontSize = 13.sp), S.text3)
                }
            }
        }
        Look.Pixel -> Column(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 22.dp).fillMaxWidth().clip(r(4.dp)).background(S.s1).border(1.dp, S.ember.copy(alpha = 0.5f), r(4.dp)),
        ) {
            HazardStripes(S.ember.copy(alpha = 0.6f), Modifier.fillMaxWidth().height(8.dp))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checker(S.ember, 8.dp); Spacer(Modifier.width(8.dp)); T("APPROVAL · TERMINAL · SUDO", Type.pixel.copy(fontSize = 9.5.sp), S.ember)
                    Grow(); T("STOP TASK", Type.pixel.copy(fontSize = 9.sp), S.text3)
                }
                T("Allow this command?", look.body().copy(fontSize = 19.sp, fontWeight = FontWeight(700)))
                T("Run the backup now so the old snapshots are cleared.", look.small().copy(fontSize = 15.sp), S.text2)
                Column(Modifier.fillMaxWidth().clip(r(2.dp)).background(S.void.copy(alpha = 0.7f)).border(1.dp, S.line, r(2.dp)).padding(12.dp)) {
                    T(buildAnnotatedString { withStyle(SpanStyle(color = S.ok)) { append("am@homelab:~$ ") }; withStyle(SpanStyle(color = S.ember)) { append("sudo ") }; append("systemctl start backup.service") }, look.code().copy(fontSize = 13.sp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyButton("D", "DENY", false, Modifier.weight(1f))
                    KeyButton("A", "ALLOW ONCE", true, Modifier.weight(1.3f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyButton("C", "THIS CHAT", false, Modifier.weight(1f), quiet = true)
                    KeyButton("∞", "ALWAYS", false, Modifier.weight(1f), quiet = true)
                }
            }
        }
        else -> {
            val glass = look == Look.Glass
            val shape: Shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
            val mod = if (glass) Modifier.glass(haze, shape, S.s1.copy(alpha = 0.72f)) else Modifier.clip(shape).background(S.s1).border(1.dp, S.line2, shape)
            Column(Modifier.fillMaxWidth().then(mod)) {
                Box(Modifier.fillMaxWidth().height(if (look == Look.Cards) 18.dp else 22.dp)) {
                    if (look == Look.Cards) HazardStripes(S.ember.copy(alpha = 0.5f), Modifier.fillMaxSize())
                    else Box(Modifier.align(Alignment.Center).width(40.dp).height(4.dp).clip(CircleShape).background(S.line2))
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgentFace(Agent.Hermes, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            T("Allow this command?", TextStyle(fontFamily = look.font, fontWeight = FontWeight(600), fontSize = 19.sp, letterSpacing = (-0.3).sp))
                            T("hermes · terminal", look.caption().copy(fontSize = 13.sp), S.text3)
                        }
                        Box(Modifier.size(38.dp).clip(CircleShape).background(S.s2), contentAlignment = Alignment.Center) { Icon(Lucide.CircleStop, S.text2, 18.dp) }
                    }
                    T("Run the backup now so the old snapshots are cleared.", look.body(), S.text2)
                    val cs = r(14.dp)
                    Row(Modifier.fillMaxWidth().clip(cs).background(S.void.copy(alpha = 0.55f)).border(1.dp, S.line, cs).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(buildAnnotatedString { withStyle(SpanStyle(color = S.ok)) { append("$ ") }; withStyle(SpanStyle(color = S.ember)) { append("sudo ") }; append("systemctl start backup.service") }, look.code().copy(fontSize = 13.sp), modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Lucide.Copy, S.text3, 16.dp)
                    }
                    if (look == Look.Cards) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { RiskTag(look, "Uses sudo"); RiskTag(look, "Starts a service") }
                        Box(Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(S.signalSoft).border(1.dp, S.signal.copy(alpha = 0.35f), CircleShape)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(0.58f).background(S.signal))
                            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Lucide.Fingerprint, Color.White, 20.dp); T("Hold to allow once", look.label().copy(fontSize = 16.sp, fontWeight = FontWeight(600)), Color.White)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("Deny", PillKind.Outline, Modifier.weight(1f)); Pill("Allow for this chat", PillKind.Secondary, Modifier.weight(1.6f))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("Deny", PillKind.Outline, Modifier.weight(1f)); Pill("Allow once", PillKind.Primary, Modifier.weight(1.4f))
                        }
                        Row(Modifier.fillMaxWidth().clip(r(14.dp)).background(S.s2.copy(alpha = if (glass) 0.6f else 1f)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                T("More options", look.label())
                                T("Allow for this chat, or always", look.caption().copy(fontSize = 13.sp), S.text3)
                            }
                            Icon(Lucide.ChevronDown, S.text3, 18.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(key: String, text: String, primary: Boolean, modifier: Modifier, quiet: Boolean = false) {
    val bg = if (primary) S.signal else Color.Transparent
    val fg = if (primary) S.onSignal else if (quiet) S.text2 else S.text
    Row(
        modifier.clip(r(3.dp)).background(bg).border(1.dp, if (primary) S.signal else S.line2, r(3.dp)).padding(horizontal = 10.dp, vertical = if (quiet) 9.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.clip(r(2.dp)).background(if (primary) S.onSignal.copy(alpha = 0.18f) else S.s3).padding(horizontal = 5.dp, vertical = 2.dp)) { T(key, Type.monoSmall, fg) }
        Spacer(Modifier.width(8.dp))
        T(text, Type.pixel.copy(fontSize = 10.sp), fg)
    }
}

@Composable
private fun RiskTag(look: Look, text: String) {
    Row(Modifier.clip(CircleShape).background(S.emberSoft).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Dot(S.ember, 5.dp); T(text, look.caption().copy(fontWeight = FontWeight(500)), S.ember)
    }
}

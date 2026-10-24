package dev.hermeskotlin.wear

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.curvedComposable
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.WatchAccent
import dev.hermeskotlin.wearlink.WatchChat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** Herald's typeface, as on the phone. */
private val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

private val Text1 = Color(0xFFF4F4F6)
private val Text2 = Color(0xFFD4D4D8)
private val Muted = Color(0xFF8A8A94)
private val Surface = Color(0xFF16161A)
private val Surface2 = Color(0xFF1C1C22)

/** Herald blue, with the phone app's dark surfaces and text. */
private val HeraldColors = ColorScheme(
    primary = HeraldBlueText,
    primaryDim = Color(0xFF4A84FE),
    primaryContainer = HeraldBlue,
    onPrimary = Color(0xFF0A0A0C),
    onPrimaryContainer = Color.White,
    background = Color.Black,
    onBackground = Text1,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Color(0xFF232329),
    onSurface = Text1,
    onSurfaceVariant = Muted,
    error = Danger,
)

internal fun watchColors(accent: WatchAccent?): ColorScheme =
    if (accent == null) HeraldColors else HeraldColors.copy(
        primary = Color(accent.text),
        primaryDim = Color(accent.dim),
        primaryContainer = Color(accent.fill),
        onPrimaryContainer = Color(accent.onFill),
    )

private fun geist(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Text1, line: Int = 0) =
    TextStyle(fontFamily = Geist, fontSize = size.sp, fontWeight = weight, color = color, lineHeight = if (line > 0) line.sp else (size * 1.3f).sp)

@Composable
fun HeraldWatchApp(
    link: WatchLink,
    store: WatchStore,
    speech: SpeechOut,
    listening: StateFlow<Listening>,
    onMic: () -> Unit,
    onFinishRecording: () -> Unit,
    onDismissListeningError: () -> Unit,
) {
    val accent by store.accent.collectAsState()
    MaterialTheme(colorScheme = watchColors(accent)) {
        AppScaffold(timeText = { EdgeStatus(link, store) }) {
            val nav = rememberSwipeDismissableNavController()
            SwipeDismissableNavHost(navController = nav, startDestination = TALK) {
                composable(TALK) {
                    TalkScreen(
                        link = link,
                        store = store,
                        speech = speech,
                        listening = listening,
                        onMic = onMic,
                        onFinishRecording = onFinishRecording,
                        onDismissListeningError = onDismissListeningError,
                        onChats = { nav.navigate(CHATS) },
                    )
                }
                composable(CHATS) {
                    ChatsScreen(link = link, store = store, onPicked = { nav.popBackStack() }, onSettings = { nav.navigate(SETTINGS) })
                }
                composable(SETTINGS) { SettingsScreen(link = link, store = store) }
            }
        }
    }
}

/**
 * The clock along the top edge, then a dot for how the phone stands (green: connected to the gateway; amber:
 * getting there; red: no phone, no Herald or signed out) and the chat's model once a turn has named it.
 */
@Composable
private fun EdgeStatus(link: WatchLink, store: WatchStore) {
    val phone by link.phone.collectAsState()
    val chat by store.chat.collectAsState()
    val dot = phoneDot(phone)
    val model = chat.model?.let(::shortModel)
    TimeText { time ->
        timeTextCurvedText(time)
        if (dot != null || model != null) timeTextSeparator()
        if (dot != null) {
            curvedComposable {
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(6.dp)
                        .background(dot, CircleShape)
                        .semantics { contentDescription = phoneDotLabel(phone) },
                )
            }
        }
        if (model != null) timeTextCurvedText(model, CurvedTextStyle(color = Muted, fontSize = 11.sp, fontFamily = Geist, fontWeight = FontWeight.Medium))
    }
}

private val Connected = Color(0xFF3FB950)

private fun phoneDot(phone: PhoneState): Color? = when (phone) {
    PhoneState.Ready -> Connected
    PhoneState.Reconnecting -> Amber
    PhoneState.NotConnected, PhoneState.NoHerald, PhoneState.SignedOut, PhoneState.Unreachable -> Danger
    PhoneState.Unknown -> null
}

private fun phoneDotLabel(phone: PhoneState): String = when (phone) {
    PhoneState.Ready -> "Connected"
    PhoneState.Reconnecting -> "Reconnecting"
    else -> "Not connected"
}

/** The model's name, short enough for the edge beside the clock. */
internal fun shortModel(name: String): String =
    if (name.length <= MAX_EDGE_MODEL) name else name.take(MAX_EDGE_MODEL - 1).trimEnd() + "…"

private const val MAX_EDGE_MODEL = 12

/**
 * The orb in the middle is the mic: tap it to talk, tap it again to stop Hermes or its voice. Above it the chat
 * it talks in; under it what's going on, then what you said and the reply. In a call, mute and hang up flank it.
 */
@Composable
private fun TalkScreen(
    link: WatchLink,
    store: WatchStore,
    speech: SpeechOut,
    listening: StateFlow<Listening>,
    onMic: () -> Unit,
    onFinishRecording: () -> Unit,
    onDismissListeningError: () -> Unit,
    onChats: () -> Unit,
) {
    val turn by link.turn.collectAsState()
    val phone by link.phone.collectAsState()
    val chat by store.chat.collectAsState()
    val speaking by speech.speaking.collectAsState()
    val mic by listening.collectAsState()
    val callState by link.call.collectAsState()
    val listState = rememberTransformingLazyColumnState()
    val current = turn
    val call = callState
    val inCall = call?.active == true
    val running = current?.running == true
    val mood = when {
        call != null -> callMood(call)
        mic.recording -> OrbMood.Listening
        current?.update?.phase == ReplyPhase.WaitingOnPhone -> OrbMood.Waiting
        running -> OrbMood.Working
        speaking -> OrbMood.Speaking
        current?.update?.phase == ReplyPhase.Failed || mic.error != null -> OrbMood.Failed
        else -> OrbMood.Idle
    }
    val onOrb = {
        when {
            mic.recording -> onFinishRecording()
            running || speaking -> link.stop()
            else -> onMic()
        }
    }

    // Each new ask starts at the top, where the orb shows it working.
    LaunchedEffect(current?.askId) { if (current != null) listState.scrollToItem(0) }

    if (inCall) {
        ScreenScaffold(modifier = Modifier.halo(mood, MaterialTheme.colorScheme.primaryContainer)) {
            CallScreen(call, mood, onMute = link::toggleMute, onEnd = link::endCall)
        }
        return
    }

    val content: @Composable (PaddingValues) -> Unit = { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item {
                // Smaller once there's more to show: a turn, or how a call ended.
                val big = mic.recording || (call == null && current == null)
                val orbSize by animateFloatAsState(if (big) 84f else 68f, tween(350), label = "orb")
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    HeraldOrb(
                        mood = mood,
                        level = mic.level,
                        context = chat.contextPercent?.let { it / 100f },
                        modifier = Modifier
                            .size(orbSize.dp)
                            .clip(CircleShape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onOrb)
                            .semantics {
                                contentDescription = when {
                                    mic.recording -> "Done talking"
                                    running || speaking -> "Stop"
                                    else -> "Talk to Hermes"
                                } + chat.contextPercent?.let { ", context $it% full" }.orEmpty()
                            },
                    )
                }
            }
            item { ChatTitle(chat.title ?: if (chat.id == null) "New chat" else "Chat", onChats) }
            item { Caption(if (call != null) callCaption(call) else caption(mood, current, mic, phone), mood) }
            val micError = mic.error
            if (micError != null) item { Notice(micError, onClick = onDismissListeningError) }
            if (call == null && current != null && !mic.recording) {
                current.said?.let { said -> item { Said(said) } }
                val update = current.update
                if (update.text.isNotBlank()) item { Reply(update.text) }
            }
        }
    }
    ScreenScaffold(scrollState = listState, modifier = Modifier.halo(mood, MaterialTheme.colorScheme.primaryContainer)) { contentPadding -> content(contentPadding) }
}

/**
 * A call, laid out as the watch's own call screen: who's on the line at the top, mute and the volume either
 * side of the orb, the time or what's happening under them, and hang up along the bottom edge. No captions:
 * the voice is heard, and the words land in the chat. Placed by fractions of the screen, as the system's is.
 */
@Composable
private fun CallScreen(call: WatchCallState, mood: OrbMood, onMute: () -> Unit, onEnd: () -> Unit) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val side = w * 0.27f
        Text(
            "Hermes",
            style = geist(20, FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = h * 0.21f - 14.dp),
        )
        CallButton(
            icon = if (call.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
            label = if (call.muted) "Unmute" else "Mute",
            color = if (call.muted) Text1 else Surface2,
            tint = if (call.muted) Color.Black else Text1,
            enabled = call.stage == CallStage.Live,
            onClick = onMute,
            modifier = Modifier.size(side).align(Alignment.Center).offset(x = -w * 0.354f),
        )
        HeraldOrb(
            mood = mood,
            level = call.level,
            // Where the system shows the caller's picture: a little above the buttons, clear of the time.
            modifier = Modifier.size(w * 0.24f).align(Alignment.Center).offset(y = -h * 0.06f),
        )
        CallButton(
            icon = R.drawable.ic_volume,
            label = "Volume",
            color = Surface2,
            tint = Text1,
            enabled = true,
            onClick = { showCallVolume(context) },
            modifier = Modifier.size(side).align(Alignment.Center).offset(x = w * 0.354f),
        )
        // The time on the call, unless there's more to say: muted, Hermes at work, connecting. Between the
        // buttons, so a long line is cut short rather than running over them.
        val since = call.liveSince?.takeIf { call.stage == CallStage.Live && !call.muted && !call.thinking }
        val status = Modifier.align(Alignment.TopCenter).widthIn(max = w * 0.44f).offset(y = h * 0.61f - 10.dp)
        if (since != null) {
            CallTimer(since, status)
        } else {
            Text(callCaption(call), style = geist(14, FontWeight.Medium, color = Muted), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = status)
        }
        EdgeButton(
            onClick = onEnd,
            // The system's height: a larger one covers the buttons beside the orb.
            buttonSize = EdgeButtonSize.Small,
            colors = ButtonDefaults.buttonColors(containerColor = EndCall, contentColor = OnEndCall),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Icon(painterResource(R.drawable.ic_call_end), contentDescription = "End call", tint = OnEndCall)
        }
    }
}

/**
 * Hermes calling for an answer, as the watch's own incoming call: who's calling at the top, why under the orb, and
 * decline and answer either side. It never shows the question itself: that's asked on the call.
 */
@Composable
fun RingScreen(title: String, approval: Boolean, onDecline: () -> Unit, onAnswer: () -> Unit, agent: Boolean = false) {
    MaterialTheme(colorScheme = HeraldColors) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val w = maxWidth
            val h = maxHeight
            val side = w * 0.27f
            Text(
                title,
                style = geist(15, FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Two lines end above the buttons beside the orb.
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = w * 0.66f).offset(y = h * 0.11f),
            )
            HeraldOrb(mood = OrbMood.Speaking, level = 0f, modifier = Modifier.size(w * 0.24f).align(Alignment.Center).offset(y = -h * 0.02f))
            CallButton(
                icon = R.drawable.ic_call_end,
                label = "Decline",
                color = EndCall,
                tint = OnEndCall,
                enabled = true,
                onClick = onDecline,
                modifier = Modifier.size(side).align(Alignment.Center).offset(x = -w * 0.354f),
            )
            CallButton(
                icon = R.drawable.ic_call,
                label = "Answer",
                color = Connected,
                tint = Color.Black,
                enabled = true,
                onClick = onAnswer,
                modifier = Modifier.size(side).align(Alignment.Center).offset(x = w * 0.354f),
            )
            Text(
                ringReason(approval, agent),
                style = geist(14, FontWeight.Medium, color = Muted),
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = w * 0.6f).offset(y = h * 0.66f),
            )
        }
    }
}

/** Why Hermes calls, never what it asks: [agent], it called on its own, and says why on the call. */
internal fun ringReason(approval: Boolean, agent: Boolean = false): String = when {
    agent -> "Calling you"
    approval -> "Needs your approval"
    else -> "Has a question for you"
}

/** The time on the call, counting: only this redraws each second, and only while it shows. */
@Composable
private fun CallTimer(since: Long, modifier: Modifier) {
    val now by produceState(SystemClock.elapsedRealtime(), since) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1000 - (value - since) % 1000)
        }
    }
    Text(callTimer(now - since), style = geist(14, FontWeight.Medium, color = Connected), textAlign = TextAlign.Center, maxLines = 1, modifier = modifier)
}

/** The system's own call volume, shown for the call's stream. */
private fun showCallVolume(context: Context) {
    context.getSystemService(AudioManager::class.java)
        ?.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
}

/** How long a call has been on, as a call screen shows it: 04:07, or 1:02:03 past an hour. */
internal fun callTimer(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    fun two(n: Long) = n.toString().padStart(2, '0')
    val clock = two(s % 3600 / 60) + ":" + two(s % 60)
    return if (s >= 3600) "${s / 3600}:$clock" else clock
}

/** Hang up's colours on the watch's own call screen. */
private val EndCall = Color(0xFFF2B8B5)
private val OnEndCall = Color(0xFF601410)

/** A round button beside the orb in a call. */
@Composable
private fun CallButton(icon: Int, label: String, color: Color, tint: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = tint),
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp), tint = tint)
    }
}

/** The orb in a call: working while it connects or Hermes is on a request, the voice's waves, or your voice. */
internal fun callMood(call: WatchCallState): OrbMood = when (call.stage) {
    CallStage.Starting, CallStage.Connecting -> OrbMood.Working
    CallStage.Live -> when {
        call.muted -> OrbMood.Idle
        call.thinking -> OrbMood.Working
        call.speaking -> OrbMood.Speaking
        else -> OrbMood.Listening
    }
    CallStage.Ended -> if (call.error != null) OrbMood.Failed else OrbMood.Idle
}

/** What a call is doing, under the orb. */
internal fun callCaption(call: WatchCallState): String = when (call.stage) {
    CallStage.Starting -> "Starting…"
    CallStage.Connecting -> "Connecting…"
    CallStage.Live -> when {
        call.muted -> "Muted"
        call.thinking -> call.working?.let { "Hermes: $it" } ?: "Hermes is on it…"
        else -> "Live"
    }
    CallStage.Ended -> call.error ?: "Call ended"
}

/** What's going on, under the orb. */
private fun caption(mood: OrbMood, turn: WatchTurn?, mic: Listening, phone: PhoneState): String? = when {
    mic.recording -> "Listening… tap when done"
    mood == OrbMood.Speaking -> "Tap to stop"
    turn != null && turn.update.phase == ReplyPhase.Failed -> turn.update.error ?: "Something went wrong."
    turn != null && turn.running -> activityLine(turn.update.phase, turn.update.activity)
    turn == null -> phoneProblem(phone) ?: "Tap to talk"
    else -> null
}

/**
 * The chat it talks in, under the orb where a round screen is widest, on up to two lines; the › says a tap
 * opens the chats.
 */
@Composable
private fun ChatTitle(title: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            "$title  ›",
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Open chats", onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 2.dp),
            style = geist(14, FontWeight.SemiBold, Text1, line = 18),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Caption(text: String?, mood: OrbMood) {
    if (text.isNullOrBlank()) return
    val color by animateColorAsState(
        when (mood) {
            OrbMood.Failed -> Danger
            OrbMood.Waiting -> Amber
            OrbMood.Idle -> Muted
            else -> MaterialTheme.colorScheme.primary
        },
        label = "caption",
    )
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        style = geist(13, FontWeight.Medium, color),
        textAlign = TextAlign.Center,
        maxLines = 3,
    )
}

/** What was said, quoted, quiet. */
@Composable
private fun Said(text: String) {
    Text(
        "“$text”",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        style = geist(13, color = Muted),
        textAlign = TextAlign.Center,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Reply(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
        style = geist(16, color = Text1, line = 22),
    )
}

/** A short notice in a dark card; a tap dismisses it. */
@Composable
private fun Notice(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = geist(13, color = Danger), textAlign = TextAlign.Center)
    }
}

/** An [accent] light rising from the bottom of the screen, brighter while something happens; amber or red to warn. */
private fun Modifier.halo(mood: OrbMood, accent: Color): Modifier = drawBehind {
    val strength = when (mood) {
        OrbMood.Idle -> 0.18f
        OrbMood.Failed -> 0.2f
        else -> 0.32f
    }
    val center = Offset(size.width / 2f, size.height * 1.08f)
    drawRect(Brush.radialGradient(listOf(orbTint(mood, accent).copy(alpha = strength), Color.Transparent), center = center, radius = size.width * 0.8f))
}

@Composable
private fun ChatsScreen(link: WatchLink, store: WatchStore, onPicked: () -> Unit, onSettings: () -> Unit) {
    val list by link.chats.collectAsState()
    val chat by store.chat.collectAsState()
    val listState = rememberTransformingLazyColumnState()
    LaunchedEffect(Unit) { link.refreshChats() }

    ScreenScaffold(scrollState = listState, modifier = Modifier.halo(OrbMood.Idle, MaterialTheme.colorScheme.primaryContainer)) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item {
                Text("Chats", modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), style = geist(17, FontWeight.SemiBold), textAlign = TextAlign.Center)
            }
            // Settings at the top, beside New chat: under a long list of chats they'd be a long way down.
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            link.newChat()
                            onPicked()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        label = {
                            Text("New chat", modifier = Modifier.fillMaxWidth(), style = geist(15, FontWeight.Medium, MaterialTheme.colorScheme.onPrimaryContainer), textAlign = TextAlign.Center, maxLines = 1)
                        },
                    )
                    FilledIconButton(
                        onClick = onSettings,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface2, contentColor = Text1),
                    ) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = Text1)
                    }
                }
            }
            val current = list
            val error = current?.error
            when {
                current == null -> item {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                }
                error != null -> item { Notice(error, onClick = link::refreshChats) }
                current.chats.isEmpty() -> item {
                    Text("No chats yet.", modifier = Modifier.fillMaxWidth().padding(12.dp), style = geist(13, color = Muted), textAlign = TextAlign.Center)
                }
                else -> current.chats.forEach { row ->
                    item(key = row.id) {
                        ChatRow(row, picked = row.id == chat.id) {
                            link.pickChat(row)
                            onPicked()
                        }
                    }
                }
            }
        }
    }
}

/** How the watch talks: live in a call (when the gateway can), and whether replies are read aloud. */
@Composable
private fun SettingsScreen(link: WatchLink, store: WatchStore) {
    val speak by store.speak.collectAsState()
    val live by store.live.collectAsState()
    val liveVoice by link.liveVoice.collectAsState()
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState, modifier = Modifier.halo(OrbMood.Idle, MaterialTheme.colorScheme.primaryContainer)) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item {
                Text("Settings", modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), style = geist(17, FontWeight.SemiBold), textAlign = TextAlign.Center)
            }
            // One short line each, and what it means under it: longer labels wrap on a round screen.
            if (liveVoice) {
                item { VoiceSwitch("Talk live", "As a voice call", live, store::setLive) }
            }
            item { VoiceSwitch("Read aloud", "Every reply", speak, store::setSpeak) }
        }
    }
}

@Composable
private fun VoiceSwitch(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        label = { Text(label, style = geist(14, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(detail, style = geist(12, color = Text2), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/** One chat: its title and how long ago it was active; the one the watch talks in has an accent dot. */
@Composable
private fun ChatRow(chat: WatchChat, picked: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = Text1, secondaryContentColor = Muted),
        icon = if (picked) {
            { Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }
        } else {
            null
        },
        secondaryLabel = ago(chat.activityAt)?.let { { Text(it, style = geist(12, color = Muted)) } },
        label = { Text(chat.title, style = geist(14, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

/** How long ago [epochSeconds] was, the way a list says it: "now", "5m", "3h", "2d". */
internal fun ago(epochSeconds: Double?, nowMillis: Long = System.currentTimeMillis()): String? {
    epochSeconds ?: return null
    val minutes = ((nowMillis / 1000.0 - epochSeconds) / 60).toLong()
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
        else -> "${minutes / (60 * 24 * 7)}w"
    }
}

private fun phoneProblem(phone: PhoneState): String? = when (phone) {
    PhoneState.NotConnected -> WatchLink.NOT_CONNECTED
    PhoneState.NoHerald -> WatchLink.NO_HERALD
    PhoneState.SignedOut -> "Sign in to Kyllene on your phone first."
    PhoneState.Unreachable -> "Kyllene can't reach your gateway. Check it on your phone."
    PhoneState.Unknown, PhoneState.Ready, PhoneState.Reconnecting -> null
}

internal fun activityLine(phase: ReplyPhase, activity: String?): String = activity ?: when (phase) {
    ReplyPhase.Transcribing -> "Writing it down…"
    ReplyPhase.Sending -> "Sending…"
    ReplyPhase.WaitingOnPhone -> "Waiting for you on your phone"
    ReplyPhase.Working -> "Thinking…"
    ReplyPhase.Done, ReplyPhase.Failed -> ""
}

private const val TALK = "talk"
private const val CHATS = "chats"
private const val SETTINGS = "settings"

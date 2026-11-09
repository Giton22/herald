package dev.hermeskotlin.ui.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.MicOff
import com.composables.icons.lucide.Phone
import com.composables.icons.lucide.PhoneOff
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.ui.voice.VoicePhase
import dev.hermeskotlin.ui.components.ProvideAppWords
import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalAccentPalette
import dev.hermeskotlin.designsystem.PureBlack
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The full screen an approval or a question rings with while the phone is locked or its screen is off: who's
 * calling and why, Answer or Decline. Always dark, as calls are. The question itself isn't shown here, over the
 * lock screen: Answer unlocks first, then Hermes asks it on the call ([InCallScreen]), or the chat opens on it.
 */
@Composable
fun IncomingCallScreen(caller: String, why: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val settings = koinInject<SettingsStore>().settings.collectAsState().value ?: return
    val palette = AccentPalette.named(settings.accent)
    hermesTheme(palette)(if (settings.pureBlack) PureBlack else ColorScheme.Dark) {
        CompositionLocalProvider(LocalAccentPalette provides palette) {
            ProvideAppWords { IncomingCallView(caller, why, onAnswer, onDecline) }
        }
    }
}

/** The screen itself, stateless so previews can draw it. */
@Composable
internal fun IncomingCallView(caller: String, why: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Theme[colors][background]).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(Res.string.call_title), style = Theme[typography][label], color = Theme[colors][textSecondary])
            Text(
                caller,
                style = Theme[typography][display],
                color = Theme[colors][text],
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(why, style = Theme[typography][body], color = Theme[colors][textSecondary], textAlign = TextAlign.Center)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 72.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CallButton(stringResource(Res.string.call_decline), Lucide.PhoneOff, Theme[colors][danger], onDecline)
            CallButton(stringResource(Res.string.call_answer), Lucide.Phone, Theme[colors][accent], onAnswer)
        }
    }
}

@Composable
private fun CallButton(name: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(color).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(icon, contentDescription = name, tint = Theme[colors][onAccent], modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(name, style = Theme[typography][label], color = Theme[colors][text])
    }
}

/**
 * A call picked up: Hermes on the line. Who it is, how long it's been, what's being said as captions, and
 * Mute and End, as a phone call shows them. No chat behind it; Hermes asks and listens by voice.
 */
@Composable
fun InCallScreen(model: PhoneCallModel) {
    val settings = koinInject<SettingsStore>().settings.collectAsState().value ?: return
    val state = model.state.collectAsState().value ?: return
    val palette = AccentPalette.named(settings.accent)
    // The call's length, from when it was picked up.
    val since = remember { TimeSource.Monotonic.markNow() }
    val seconds by produceState(0L) {
        while (true) {
            value = since.elapsedNow().inWholeSeconds
            delay(1_000)
        }
    }
    hermesTheme(palette)(if (settings.pureBlack) PureBlack else ColorScheme.Dark) {
        CompositionLocalProvider(LocalAccentPalette provides palette) {
            ProvideAppWords { InCallView(state, seconds, onMute = model::toggleMute, onEnd = model::hangUp) }
        }
    }
}

/** The call screen itself, stateless so previews can draw it. */
@Composable
internal fun InCallView(state: PhoneCallState, seconds: Long, onMute: () -> Unit, onEnd: () -> Unit) {
    val voice = state.voice
    Box(
        Modifier.fillMaxSize().background(Theme[colors][background]).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                state.caller,
                style = Theme[typography][display],
                color = Theme[colors][text],
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(callStatusText(callStatus(state, seconds)), style = Theme[typography][body], color = Theme[colors][textSecondary], textAlign = TextAlign.Center)
        }
        // What was said last, as captions: Hermes's words, or yours.
        val caption = state.error ?: voice.caption
        if (caption != null) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.error == null) {
                    Text(
                        if (voice.captionIsVoice) "Hermes" else stringResource(Res.string.voice_you),
                        style = Theme[typography][label],
                        color = Theme[colors][textSecondary],
                    )
                }
                Text(
                    caption,
                    style = Theme[typography][body],
                    color = if (state.error != null) Theme[colors][danger] else Theme[colors][text],
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // A live call mutes from the start; the chained chat once it listens.
        val mutable = voice.live || voice.phase != VoicePhase.Off && voice.phase != VoicePhase.Connecting
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 72.dp),
            horizontalArrangement = if (mutable) Arrangement.SpaceBetween else Arrangement.Center,
        ) {
            if (mutable) {
                CallButton(
                    stringResource(if (voice.muted) Res.string.voice_unmute else Res.string.voice_mute),
                    if (voice.muted) Lucide.MicOff else Lucide.Mic,
                    if (voice.muted) Theme[colors][accent] else Theme[colors][surface3],
                    onMute,
                )
            }
            CallButton(stringResource(Res.string.voice_end), Lucide.PhoneOff, Theme[colors][danger], onEnd)
        }
    }
}

/** Under the caller: the call's state, or its length with what Hermes is doing now. */
internal fun callStatus(state: PhoneCallState, seconds: Long): CallStatus {
    val voice = state.voice
    if (state.ended) return CallStatus.Ended
    val length = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    val doing = when (voice.phase) {
        VoicePhase.Off, VoicePhase.Connecting -> return CallStatus.Connecting
        VoicePhase.Listening -> if (voice.muted) CallStatus.Doing.Muted else CallStatus.Doing.Listening
        VoicePhase.Transcribing -> CallStatus.Doing.Listening
        VoicePhase.Thinking -> if (voice.working != null) CallStatus.Doing.Working else CallStatus.Doing.Thinking
        VoicePhase.Speaking -> CallStatus.Doing.Speaking
    }
    return CallStatus.Live(length, doing, voice.working.takeIf { doing == CallStatus.Doing.Working })
}

/** What [callStatus] found; [callStatusText] words it. */
internal sealed interface CallStatus {
    data object Ended : CallStatus
    data object Connecting : CallStatus

    /** [length] as m:ss; [tool] is what Hermes runs, while [doing] is [Doing.Working]. */
    data class Live(val length: String, val doing: Doing, val tool: String? = null) : CallStatus

    enum class Doing { Listening, Muted, Thinking, Working, Speaking }
}

@Composable
internal fun callStatusText(status: CallStatus): String = when (status) {
    CallStatus.Ended -> stringResource(Res.string.call_ended)
    CallStatus.Connecting -> stringResource(Res.string.voice_connecting)
    is CallStatus.Live -> {
        val doing = when (status.doing) {
            CallStatus.Doing.Listening -> stringResource(Res.string.call_listening)
            CallStatus.Doing.Muted -> stringResource(Res.string.voice_muted)
            CallStatus.Doing.Thinking -> stringResource(Res.string.call_thinking)
            CallStatus.Doing.Working -> stringResource(Res.string.call_working, status.tool.orEmpty())
            CallStatus.Doing.Speaking -> stringResource(Res.string.call_speaking)
        }
        stringResource(Res.string.call_status, status.length, doing)
    }
}

/**
 * Why it rings, in a few words: what the agent waits for, never the command or the question itself. In English,
 * for the call's notification, whose other words are English too; [IncomingCallScreen] shows [callReasonText].
 */
fun callReason(approval: Boolean): String = if (approval) "Needs your approval" else "Has a question for you"

/** Why a call the agent placed rings, over the lock screen: not what it's about, which Hermes says on the line. */
fun agentCallReason(): String = "Calling you"

/** [callReason] in the app's language, for the call screen. */
@Composable
fun callReasonText(approval: Boolean): String =
    stringResource(if (approval) Res.string.call_reason_approval else Res.string.call_reason_question)

/** [agentCallReason] in the app's language, for the call screen. */
@Composable
fun agentCallReasonText(): String = stringResource(Res.string.call_reason_agent)

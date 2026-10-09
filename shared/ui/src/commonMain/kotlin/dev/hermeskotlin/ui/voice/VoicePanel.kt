package dev.hermeskotlin.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.MicOff
import com.composables.icons.lucide.PhoneOff
import com.composables.icons.lucide.SkipForward
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Takes the composer's place during a voice chat, the chat staying in view above it: an orb that swells with
 * your voice and ripples while the voice talks, what's happening, the words just said as captions, the tool
 * Hermes is running, and round buttons. A GPT-Live call has Mute (you talk over the voice to cut in); the
 * chained chat has Skip while it reads a reply. What you say sends itself; saying "stop" ends it.
 */
@Composable
internal fun VoicePanel(hazeState: HazeState, state: VoiceChatState, onSkip: () -> Unit, onMute: () -> Unit, onEnd: () -> Unit, onExpand: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val page = Theme[colors][background]
    val frosted = remember(page) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(24.dp)
            backgroundColor(page)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .clip(shape)
            .hazeBlur(input = HazeInput.Sources(hazeState), style = frosted)
            .background(Theme[colors][surface].copy(alpha = 0.6f))
            .border(1.dp, Theme[colors][strokeStrong], shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The orb opens the voice chat over the whole screen again.
        Orb(
            state,
            Modifier
                .size(112.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Show voice chat full screen", onClick = onExpand)
                .semantics { contentDescription = "Voice chat" },
        )
        Text(
            label(state),
            style = Theme[typography][body],
            fontWeight = FontWeight.SemiBold,
            color = Theme[colors][textColor],
            textAlign = TextAlign.Center,
        )
        val caption = state.caption
        Text(
            caption?.let { if (state.captionIsVoice) it else "“$it”" } ?: hint(state),
            style = Theme[typography][bodySmall],
            color = if (caption != null) Theme[colors][textSecondary] else Theme[colors][textTertiary],
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 4.dp),
        )
        AnimatedVisibility(state.working != null, enter = fadeIn(), exit = fadeOut()) {
            WorkingChip(state.working.orEmpty())
        }
        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                state.live && state.phase != VoicePhase.Connecting -> RoundButton(
                    if (state.muted) Lucide.MicOff else Lucide.Mic,
                    contentDescription = if (state.muted) "Unmute" else "Mute",
                    fill = if (state.muted) Theme[colors][textColor] else Theme[colors][textColor].copy(alpha = 0.08f),
                    tint = if (state.muted) Theme[colors][background] else Theme[colors][textColor],
                    onClick = onMute,
                )
                !state.live && state.phase == VoicePhase.Speaking -> RoundButton(
                    Lucide.SkipForward,
                    contentDescription = "Skip the reply",
                    fill = Theme[colors][textColor].copy(alpha = 0.08f),
                    tint = Theme[colors][textColor],
                    onClick = onSkip,
                )
            }
            RoundButton(Lucide.PhoneOff, contentDescription = "End voice chat", fill = Theme[colors][danger], tint = Color.White, onClick = onEnd)
        }
    }
}

internal fun label(state: VoiceChatState) = when (state.phase) {
    VoicePhase.Connecting -> "Connecting…"
    VoicePhase.Listening -> if (state.muted) "Muted" else if (state.hearing) "Hearing you…" else "Listening…"
    VoicePhase.Transcribing -> "Catching that…"
    VoicePhase.Thinking -> "Thinking…"
    VoicePhase.Speaking -> "Speaking…"
    VoicePhase.Off -> ""
}

/** What to do now, while there are no words to show. */
internal fun hint(state: VoiceChatState) = when {
    state.muted -> "Tap the microphone to talk again"
    state.phase == VoicePhase.Connecting -> "Setting up the call"
    state.phase == VoicePhase.Thinking -> "Hermes is on it"
    state.live && state.phase == VoicePhase.Speaking -> "Talk over it to cut in"
    state.phase == VoicePhase.Speaking -> "Tap skip to talk again"
    else -> "Just talk · say “stop” to end"
}

/** The tool Hermes is running for the request. */
@Composable
private fun WorkingChip(tool: String) {
    Row(
        Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(Theme[colors][accent].copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spinner(Modifier.size(12.dp))
        Text(
            tool,
            style = Theme[typography][caption],
            color = Theme[colors][textSecondary],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RoundButton(icon: ImageVector, contentDescription: String, fill: Color, tint: Color, onClick: () -> Unit) {
    val background by animateColorAsState(fill)
    UnstyledButton(
        onClick = onClick,
        modifier = Modifier.size(60.dp).clip(CircleShape),
        indication = rememberColoredIndication(tint),
    ) {
        Box(Modifier.size(60.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
            UnstyledIcon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * The voice's orb in the accent colour: it swells with your voice while listening, sends ripples out while
 * the voice talks, breathes with an arc circling it while Hermes works or the call connects, and greys out
 * when muted.
 */
@Composable
private fun Orb(state: VoiceChatState, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(if (state.muted) Theme[colors][textTertiary] else Theme[colors][accentText])
    val time by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
    )
    // Square root, so a phone mic's quiet range still visibly moves it.
    val listening = state.phase == VoicePhase.Listening && !state.muted
    val level by animateFloatAsState(
        if (listening) sqrt((state.level * 4f).coerceIn(0f, 1f)) else 0f,
        spring(stiffness = Spring.StiffnessMediumLow),
    )
    val speaking = state.phase == VoicePhase.Speaking
    val working = state.phase == VoicePhase.Thinking || state.phase == VoicePhase.Transcribing || state.phase == VoicePhase.Connecting
    Canvas(modifier) {
        val core = size.minDimension / 2f * 0.5f
        val breath = sin(time * 2f * PI.toFloat()) * 0.04f
        if (speaking) {
            // Ripples leaving the orb, three at a time.
            for (i in 0 until 3) {
                val wave = (time * 1.5f + i / 3f) % 1f
                drawCircle(tint.copy(alpha = 0.3f * (1f - wave)), radius = core * (1f + 0.95f * wave))
            }
        } else {
            for (i in 0 until 2) {
                drawCircle(tint.copy(alpha = 0.16f - i * 0.06f), radius = core * (1.15f + i * 0.25f + level * (0.6f - i * 0.15f)))
            }
        }
        if (working) {
            val ring = core * 1.55f
            rotate(time * 360f) {
                drawArc(
                    tint.copy(alpha = 0.8f),
                    startAngle = 0f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(center.x - ring, center.y - ring),
                    size = Size(ring * 2, ring * 2),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        val radius = core * (1f + breath + level * 0.25f)
        drawCircle(
            Brush.radialGradient(
                listOf(lerp(tint, Color.White, 0.45f), tint, lerp(tint, Color.Black, 0.2f)),
                center = Offset(center.x - radius * 0.35f, center.y - radius * 0.4f),
                radius = radius * 1.6f,
            ),
            radius = radius,
        )
    }
}

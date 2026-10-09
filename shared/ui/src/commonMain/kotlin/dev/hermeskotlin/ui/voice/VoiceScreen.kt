package dev.hermeskotlin.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Captions
import com.composables.icons.lucide.CaptionsOff
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Keyboard
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.MicOff
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A voice chat over the whole screen: the chat's name on top, what was said last as large captions, an orb
 * that moves with your voice or the reply, what's happening, and Mute (or Skip), End and Type round the
 * bottom. The chevron (or Back) folds it to the panel above the chat; Type ends the call for the keyboard.
 */
@Composable
internal fun VoiceScreen(
    chatTitle: String,
    state: VoiceChatState,
    /** Hermes's last reply, shown dimmed above what you're saying. */
    lastReply: String?,
    captions: Boolean,
    onToggleCaptions: () -> Unit,
    onMinimize: () -> Unit,
    onSkip: () -> Unit,
    onMute: () -> Unit,
    onEnd: () -> Unit,
    onType: () -> Unit,
) {
    PlatformBackHandler(enabled = true, onBack = onMinimize)
    val glow = Theme[colors][accent].copy(alpha = 0.22f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            // Swallows taps so nothing reaches the chat beneath.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.width * 0.9f
            drawCircle(
                Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width / 2, size.height * 0.82f), radius = radius),
                radius = radius,
                center = Offset(size.width / 2, size.height * 0.82f),
            )
        }
        Column(Modifier.fillMaxSize().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RingButton(Lucide.ChevronDown, "Show the chat", onMinimize)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Voice chat", style = Theme[typography][body].copy(fontSize = 14.sp, lineHeight = 18.sp), fontWeight = FontWeight.SemiBold, color = Theme[colors][textColor], maxLines = 1)
                    Text(chatTitle, style = Theme[typography][caption].copy(fontSize = 12.sp, lineHeight = 16.sp), color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RingButton(if (captions) Lucide.Captions else Lucide.CaptionsOff, if (captions) "Hide captions" else "Show captions", onToggleCaptions)
            }
            Transcript(
                state,
                lastReply.takeIf { captions },
                captions,
                Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            )
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                BigOrb(state, Modifier.size(168.dp))
                Column(
                    Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(label(state), style = Theme[typography][body].copy(fontSize = 17.sp, lineHeight = 22.sp), fontWeight = FontWeight.SemiBold, color = Theme[colors][textColor], textAlign = TextAlign.Center)
                    Text(hint(state), style = Theme[typography][bodySmall].copy(fontSize = 13.sp), color = Theme[colors][textTertiary], textAlign = TextAlign.Center)
                    AnimatedVisibility(state.working != null, enter = fadeIn(), exit = fadeOut()) {
                        WorkingChip(state.working.orEmpty())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        state.live && state.phase != VoicePhase.Connecting ->
                            SideButton(if (state.muted) Lucide.MicOff else Lucide.Mic, if (state.muted) "Unmute" else "Mute", onMute, on = state.muted)
                        !state.live && state.phase == VoicePhase.Speaking -> SideButton(Lucide.SkipForward, "Skip the reply", onSkip)
                        // An empty slot keeps End in the middle.
                        else -> Spacer(Modifier.size(52.dp))
                    }
                    EndButton(onEnd)
                    SideButton(Lucide.Keyboard, "Type instead", onType)
                }
            }
        }
    }
}

/** The last reply, dimmed, over what was just said, large; the newest words sit nearest the orb. */
@Composable
private fun Transcript(state: VoiceChatState, lastReply: String?, captions: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom)) {
        if (!captions) return@Column
        val said = state.caption
        val mine = said != null && !state.captionIsVoice
        // While you talk, the reply you're answering stays above; while Hermes talks, its words are the caption.
        if (lastReply != null && (said == null || mine)) {
            Line("Hermes", lastReply, Modifier.alpha(0.55f), big = false, you = false, lines = if (said == null) 8 else 4)
        }
        if (said != null) Line(if (mine) "You" else "Hermes", said, Modifier, big = mine, you = mine, lines = 8)
    }
}

@Composable
private fun Line(who: String, words: String, modifier: Modifier, big: Boolean, you: Boolean, lines: Int) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(who.uppercase(), style = Theme[typography][eyebrow], color = if (you) Theme[colors][accentText] else Theme[colors][textTertiary])
        Text(
            words,
            style = if (big) {
                Theme[typography][body].copy(fontSize = 24.sp, lineHeight = 31.sp, letterSpacing = (-0.02).em, fontWeight = FontWeight.Medium)
            } else {
                Theme[typography][body].copy(fontSize = 16.sp, lineHeight = 24.sp)
            },
            color = if (big) Theme[colors][textColor] else Theme[colors][textSecondary],
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A 40dp round surface button with a hairline ring, in a full-size touch target. */
@Composable
private fun RingButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(MinTouchTarget)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Theme[colors][surface])
                .border(1.dp, Theme[colors][stroke], CircleShape)
                .indication(interaction, rememberColoredIndication(Theme[colors][textColor])),
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(19.dp))
        }
    }
}

/** A 52dp round button beside End; [on] fills it in, as Mute does while muted. */
@Composable
private fun SideButton(icon: ImageVector, description: String, onClick: () -> Unit, on: Boolean = false) {
    val fill by animateColorAsState(if (on) Theme[colors][textColor] else Theme[colors][surface3])
    val tint = if (on) Theme[colors][background] else Theme[colors][textSecondary]
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** The red End pill. */
@Composable
private fun EndButton(onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(Theme[colors][danger])
            .clickable(role = Role.Button, onClickLabel = "End voice chat", onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.X, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text("End", style = Theme[typography][body].copy(fontSize = 15.sp), fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
    }
}

/** The tool Hermes is running for the request. */
@Composable
private fun WorkingChip(tool: String) {
    Row(
        Modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Theme[colors][accentSoft])
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spinner(Modifier.size(12.dp), color = Theme[colors][accentText])
        Text(tool, style = Theme[typography][caption], color = Theme[colors][textSecondary], maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Two soft accent rings round a lit accent ball with five white bars: the outer ring breathes and swells
 * with your voice, the bars rise with it while you talk and wave while the voice speaks; an arc circles it
 * while Hermes works or the call connects. Muted, it all goes grey and still.
 */
@Composable
private fun BigOrb(state: VoiceChatState, modifier: Modifier = Modifier) {
    val muted = state.muted
    val tint by animateColorAsState(if (muted) Theme[colors][textMuted] else Theme[colors][accent])
    val soft by animateColorAsState(if (muted) Theme[colors][surface3] else Theme[colors][accentSoft])
    val arc = Theme[colors][accentText]
    val time by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_200, easing = LinearEasing)),
    )
    // Square root, so a phone mic's quiet range still visibly moves it.
    val listening = state.phase == VoicePhase.Listening && !muted
    val level by animateFloatAsState(
        if (listening) sqrt((state.level * 4f).coerceIn(0f, 1f)) else 0f,
        spring(stiffness = Spring.StiffnessMediumLow),
    )
    val speaking = state.phase == VoicePhase.Speaking
    val working = state.phase == VoicePhase.Thinking || state.phase == VoicePhase.Transcribing || state.phase == VoicePhase.Connecting
    Canvas(modifier.clearAndSetSemantics {}) {
        val outer = size.minDimension / 2f
        val wave = sin(time * 2f * PI.toFloat())
        drawCircle(soft, radius = outer * (0.9f + 0.05f * wave + 0.1f * level))
        drawCircle(soft, radius = outer * 0.76f)
        val core = outer * 0.62f
        // The glow under the ball, a little low, as if it were lit from above.
        drawCircle(
            Brush.radialGradient(listOf(tint.copy(alpha = 0.45f), Color.Transparent), center = Offset(center.x, center.y + 10.dp.toPx()), radius = core * 1.35f),
            radius = core * 1.35f,
            center = Offset(center.x, center.y + 10.dp.toPx()),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(lerp(tint, Color.White, 0.45f), tint),
                center = Offset(center.x - core * 0.3f, center.y - core * 0.4f),
                radius = core * 1.3f,
            ),
            radius = core,
        )
        val bar = 4.dp.toPx()
        val gap = 4.dp.toPx()
        BAR_HEIGHTS.forEachIndexed { i, full ->
            val scale = when {
                muted -> 0.2f
                speaking -> 0.45f + 0.55f * ((sin((time * 2f + i * 0.21f) * 2f * PI.toFloat()) + 1f) / 2f)
                listening -> 0.3f + 0.7f * level
                else -> 0.3f
            }
            val h = full.dp.toPx() * scale
            val x = center.x + (i - 2) * (bar + gap) - bar / 2
            drawRoundRect(
                Color.White.copy(alpha = BAR_ALPHAS[i]),
                topLeft = Offset(x, center.y - h / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
        if (working) {
            val ring = outer * 0.84f
            rotate(time * 360f) {
                drawArc(
                    arc,
                    startAngle = 0f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(center.x - ring, center.y - ring),
                    size = Size(ring * 2, ring * 2),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

private val BAR_HEIGHTS = listOf(18, 34, 24, 40, 20)
private val BAR_ALPHAS = listOf(0.8f, 1f, 0.9f, 1f, 0.8f)

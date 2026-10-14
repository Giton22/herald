package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.designsystem.warningSoft
import dev.hermeskotlin.ui.sessions.OtherChatEvent
import dev.hermeskotlin.ui.sessions.SessionsBadge
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.Clock

/**
 * The top bar's Sessions button, with a dot when another chat needs the user: the accent for a reply, the
 * warning colour when one waits on an approval or answer. A long press goes to the next chat needing the user.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionsButton(badge: SessionsBadge, onClick: () -> Unit, onNext: (() -> Unit)?) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(MinTouchTarget)
            .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onNext?.let { next ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        next()
                    }
                },
                onClickLabel = "Open sessions",
                onLongClickLabel = if (onNext != null) "Next chat needing you" else null,
                role = Role.Button,
                interactionSource = null,
                indication = rememberColoredIndication(Theme[colors][textColor]),
            )
            .semantics { contentDescription = badge.description },
        contentAlignment = Alignment.Center,
    ) {
        UnstyledIcon(Lucide.PanelLeft, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(19.dp))
        if (!badge.none) {
            val dot = if (badge.urgent) Theme[colors][warning] else Theme[colors][accent]
            // Ringed in the bar's background, so it reads over the icon's corner.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(start = 16.dp, bottom = 16.dp)
                    .size(10.dp)
                    .background(Theme[colors][background], CircleShape)
                    .padding(2.dp)
                    .background(dot, CircleShape),
            )
        }
    }
}

/**
 * The toast over the chat saying another chat replied or waits on the user, drawn as the design's top toast
 * (SystemStates, "Version … is out"): a floating card with the tone's icon in a tile, what happened over the start of
 * it, Open and ×. Next stays on a long press of Sessions. It goes after [BANNER_MILLIS] (or as long as the
 * accessibility settings ask; with a screen reader on, until it's closed), and a sideways swipe takes it away. The
 * view model holds the event, so a rotation doesn't show it twice or start its time over.
 */
@Composable
internal fun OtherChatBanner(
    event: OtherChatEvent,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accessibility = LocalAccessibilityManager.current
    // A preview is there to be read, so it stays a little longer.
    val base = if (event.preview != null) PREVIEW_BANNER_MILLIS else BANNER_MILLIS
    val timeout = remember(accessibility, base) {
        accessibility?.calculateRecommendedTimeoutMillis(base, containsIcons = true, containsText = true, containsControls = true)
            ?: base
    }
    LaunchedEffect(event.chatId, event.atMillis, timeout) {
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        val left = timeout - (Clock.System.now().toEpochMilliseconds() - event.atMillis)
        if (left > 0) delay(left)
        onDismiss()
    }
    var offset by remember(event.chatId, event.atMillis) { mutableFloatStateOf(0f) }
    // The swipe's handler outlives recompositions (the preview filling in), so it calls the latest dismiss.
    val dismiss by rememberUpdatedState(onDismiss)
    val dismissAt = with(LocalDensity.current) { 96.dp.toPx() }
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .padding(horizontal = 14.dp)
            // A floating card, not a bar across a tablet or an unfolded phone.
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .graphicsLayer {
                translationX = offset
                alpha = 1f - (abs(offset) / (dismissAt * 2)).coerceIn(0f, 0.8f)
            }
            .pointerInput(event.chatId, event.atMillis) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (abs(offset) > dismissAt) dismiss() else offset = 0f },
                    onDragCancel = { offset = 0f },
                ) { _, delta -> offset += delta }
            }
            // The design's float shadow: a strong hairline and the undo toast's soft drop.
            .dropShadow(shape, Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.22f), offset = DpOffset(0.dp, 8.dp)))
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][strokeStrong], shape)
            // The whole card opens the chat; Open stays for those who look for a button.
            .clickable(onClickLabel = "Open", role = Role.Button, onClick = onOpen)
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Amber for a chat waiting on the user, the accent for a reply.
        Box(
            Modifier
                .size(32.dp)
                .background(Theme[colors][if (event.waiting) warningSoft else accentSoft], RoundedCornerShape(Theme[radii][radiusSmall])),
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(
                if (event.waiting) Lucide.ShieldAlert else Lucide.MessageCircle,
                contentDescription = null,
                tint = Theme[colors][if (event.waiting) warning else accentText],
                modifier = Modifier.size(16.dp),
            )
        }
        Column(
            // Announced once as it comes; a newer event replacing it is announced in turn.
            Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                event.message,
                style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.Medium),
                color = Theme[colors][textColor],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            event.preview?.let { preview ->
                Text(
                    preview,
                    style = Theme[typography][caption],
                    color = Theme[colors][textTertiary],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // A 36dp pill, as the design's, inside a full-height touch target (as the toast's action).
        val openInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .defaultMinSize(minHeight = MinTouchTarget)
                .clickable(openInteraction, indication = null, role = Role.Button, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .clip(CircleShape)
                    .background(Theme[colors][accent], CircleShape)
                    .indication(openInteraction, rememberColoredIndication(Theme[colors][onAccent]))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Open", style = Theme[typography][label].copy(fontWeight = FontWeight.SemiBold), color = Theme[colors][onAccent])
            }
        }
        IconButton(Lucide.X, contentDescription = "Dismiss", onClick = onDismiss, tint = Theme[colors][textMuted], iconSize = 15.dp)
    }
}

private const val BANNER_MILLIS = 5_000L

/** With a preview to read. */
private const val PREVIEW_BANNER_MILLIS = 7_000L

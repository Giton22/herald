package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CornerDownRight
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageCircleOff
import com.composables.icons.lucide.SquareTerminal
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composables.icons.lucide.Send
import dev.hermeskotlin.core.chat.DeliveryOutcome
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.TranscriptEvent
import dev.hermeskotlin.core.chat.messageTarget
import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.ui.bots.BotAvatar
import dev.hermeskotlin.ui.bots.LocalBotFaces

/**
 * A row the machinery wrote into the chat: a single quiet line saying what happened, which opens to show
 * the words or output behind it. Desktop shows most of these as collapsed notes too; delivery receipts,
 * which Desktop leaves as raw process output, say in words how a message to another bot ended.
 */
@Composable
internal fun TranscriptEventRow(event: TranscriptEvent) {
    when (event) {
        is TranscriptEvent.FromBot -> FromBotNote(event)
        is TranscriptEvent.Delivery -> DeliveryLine(event)
        is TranscriptEvent.Process -> FoldedLine(
            icon = Lucide.SquareTerminal,
            title = event.title,
            tint = if (event.failed) Theme[colors][danger] else Theme[colors][textTertiary],
            body = event.output.takeIf { it.isNotBlank() }?.let { { Monospace(it) } },
        )
        is TranscriptEvent.Routine -> FoldedLine(
            icon = Lucide.AlarmClock,
            title = "Routine “${event.name}” ran",
            body = event.body.takeIf { it.isNotBlank() }?.let { { MarkdownText(it) } },
        )
        is TranscriptEvent.FailedTurn -> FoldedLine(
            icon = Lucide.CircleAlert,
            title = event.text.ifBlank { "This turn did not complete." },
            tint = Theme[colors][danger],
            body = null,
        )
        is TranscriptEvent.Label -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(event.text, style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
    }
}

/** Another bot's message: who sent it, with its face, and its words a tap away (the first lines show). */
@Composable
private fun FromBotNote(event: TranscriptEvent.FromBot) {
    val faces = LocalBotFaces.current
    val bot = remember(faces, event.handle, event.sender) { faces.find(event.handle) ?: faces.find(event.sender) }
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(onClickLabel = if (open) "Hide message" else "Show message") { open = !open }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (bot != null) {
                BotAvatar(bot, faces.picture(bot), size = 20.dp)
            } else {
                UnstyledIcon(Lucide.Bot, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(16.dp))
            }
            Text(
                "Message from ${bot?.label ?: event.sender}",
                style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.SemiBold),
                color = Theme[colors][text],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Chevron(open)
        }
        if (open) {
            if (event.body.isNotBlank()) MarkdownText(event.body)
        } else if (event.body.isNotBlank()) {
            Text(
                event.body.replace(Regex("\\s+"), " "),
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How a message to another bot ended, in words: their reply, that it's still on its way, or why it failed. */
@Composable
private fun DeliveryLine(event: TranscriptEvent.Delivery) {
    val faces = LocalBotFaces.current
    val who = event.target?.let { target -> faces.find(target)?.label ?: "@$target" } ?: "the other bot"
    when (val outcome = event.outcome) {
        is DeliveryOutcome.Replied -> FoldedLine(Lucide.MessageCircle, "Reply from $who", body = { MarkdownText(outcome.text) })
        DeliveryOutcome.NoReply -> FoldedLine(Lucide.MessageCircleOff, "$who read it and chose not to reply", body = null)
        is DeliveryOutcome.Waiting -> FoldedLine(
            Lucide.Hourglass,
            "Still waiting on $who · don't send it again",
            tint = Theme[colors][warning],
            body = outcome.detail?.let { { Monospace(it) } },
        )
        is DeliveryOutcome.Failed -> FoldedLine(
            Lucide.CircleAlert,
            "Couldn't reach $who · ${outcome.label}",
            tint = Theme[colors][danger],
            body = outcome.detail?.let { { Monospace(it) } },
        )
    }
}

/** A reply that answered another bot's message: folded under who it went to, unless opened. */
@Composable
internal fun RepliedToFold(to: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val faces = LocalBotFaces.current
    val name = remember(faces, to) { faces.find(to)?.label ?: to }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
                .clickable(onClickLabel = if (open) "Hide reply" else "Show reply") { open = !open }
                .heightIn(min = MinTouchTarget)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.CornerDownRight, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            Text("Replied to $name", style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
            Chevron(open)
        }
        AnimatedVisibility(open) { content() }
    }
}

/** This bot's `message_agent` call: to whom, and whether it went; the message itself a tap away. */
@Composable
internal fun MessagedLine(tool: ToolActivity) {
    val target = remember(tool.input) { tool.input?.let { messageTarget(JsonPrimitive(it)) } }
    val message = remember(tool.input) { tool.input?.let(::messageText) }
    val who = target?.let { "@$it" } ?: "another bot"
    val (title, tint) = when {
        tool.running -> "Messaging $who…" to Theme[colors][textTertiary]
        tool.failed -> "Couldn't message $who" to Theme[colors][danger]
        else -> "Messaged $who" to Theme[colors][textTertiary]
    }
    FoldedLine(
        icon = Lucide.Send,
        title = title,
        tint = tint,
        body = when {
            tool.failed -> tool.output?.let { { Monospace(it) } }
            message != null -> { { MarkdownText(message) } }
            else -> null
        },
    )
}

private fun messageText(input: String): String? = runCatching {
    ((HermesJson.parseToJsonElement(input) as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
}.getOrNull()?.takeIf { it.isNotBlank() }

/** One line with an icon; tapping opens [body] beneath it when there is one. */
@Composable
private fun FoldedLine(icon: ImageVector, title: String, tint: Color = Theme[colors][textTertiary], body: (@Composable () -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
                .then(if (body != null) Modifier.clickable(onClickLabel = if (open) "Hide details" else "Show details") { open = !open } else Modifier)
                .heightIn(min = 36.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Text(
                title,
                style = Theme[typography][bodySmall],
                color = if (tint == Theme[colors][textTertiary]) Theme[colors][textSecondary] else tint,
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (body != null) Chevron(open)
        }
        if (open && body != null) Box(Modifier.padding(start = 27.dp)) { body() }
    }
}

@Composable
private fun Monospace(text: String) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Text(
        text,
        style = Theme[typography][code],
        color = Theme[colors][textSecondary],
        modifier = Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    )
}

@Composable
private fun Chevron(open: Boolean) {
    UnstyledIcon(
        if (open) Lucide.ChevronDown else Lucide.ChevronRight,
        contentDescription = null,
        tint = Theme[colors][textTertiary],
        modifier = Modifier.size(14.dp),
    )
}

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
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.botLook
import dev.hermeskotlin.designsystem.components.Spinner
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

/** Another bot's message, as that bot speaking: its face beside a bubble in its colour. */
@Composable
private fun FromBotNote(event: TranscriptEvent.FromBot) {
    val faces = LocalBotFaces.current
    val bot = remember(faces, event.handle, event.sender) { faces.find(event.handle) ?: faces.find(event.sender) }
    BotSays(bot, fallbackName = event.sender, note = null, words = event.body)
}

/** How a message to another bot ended, in words: their reply, that it's still on its way, or why it failed. */
@Composable
private fun DeliveryLine(event: TranscriptEvent.Delivery) {
    val faces = LocalBotFaces.current
    val bot = remember(faces, event.target) { faces.find(event.target) }
    val who = bot?.label ?: event.target?.let { "@$it" } ?: "the other bot"
    when (val outcome = event.outcome) {
        // Their answer is them speaking, like any message of theirs.
        is DeliveryOutcome.Replied -> BotSays(bot, fallbackName = who, note = "replied", words = outcome.text)
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

/**
 * This bot's answer to another bot's message, as a note addressed to it: "To X" with X's face and the
 * first lines; the whole reply, with its tools and reasoning, a tap away.
 */
@Composable
internal fun RepliedToFold(to: String, preview: String, content: @Composable () -> Unit) {
    val faces = LocalBotFaces.current
    val bot = remember(faces, to) { faces.find(to) }
    ToBot(bot, fallbackName = to, note = "reply", words = preview, sending = false, failed = false, full = content)
}

/** This bot's `message_agent` call, as a note addressed to the other bot: "To X" and the message. */
@Composable
internal fun MessagedLine(tool: ToolActivity) {
    val target = remember(tool.input) { tool.input?.let { messageTarget(JsonPrimitive(it)) } }
    val message = remember(tool.input) { tool.input?.let(::messageText) }
    val faces = LocalBotFaces.current
    val bot = remember(faces, target) { faces.find(target) }
    ToBot(
        bot,
        fallbackName = target?.let { "@$it" } ?: "another bot",
        note = when {
            tool.running -> "sending…"
            tool.failed -> "not sent"
            else -> "message"
        },
        words = if (tool.failed) tool.output.orEmpty() else message.orEmpty(),
        sending = tool.running,
        failed = tool.failed,
    )
}

/** [bot] speaking: its face beside a bubble tinted in its colour, its name over the first lines. */
@Composable
private fun BotSays(bot: Bot?, fallbackName: String, note: String?, words: String) {
    val faces = LocalBotFaces.current
    val tint = bot?.let { remember(it.name, it.uiMeta) { Color(0xFF000000 or botLook(it).color.toLong()) } } ?: Theme[colors][textSecondary]
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    Row(Modifier.fillMaxWidth().padding(end = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (bot != null) {
            BotAvatar(bot, faces.picture(bot), size = 28.dp)
        } else {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                UnstyledIcon(Lucide.Bot, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .clip(shape)
                .background(tint.copy(alpha = 0.12f), shape)
                .border(1.dp, tint.copy(alpha = 0.28f), shape)
                .clickable(enabled = words.isNotBlank(), onClickLabel = if (open) "Show less" else "Show all") { open = !open }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bot?.label ?: fallbackName,
                    style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.SemiBold),
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                note?.let { Text(it, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1) }
            }
            when {
                words.isBlank() -> Unit
                open -> MarkdownText(words)
                else -> Text(words.toPreview(), style = Theme[typography][bodySmall], color = Theme[colors][text], maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Something this bot sent another one: a note set to the right, addressed "To X" with X's face. Shows the
 * first lines; a tap shows [full] when given (the whole reply), else the whole text.
 */
@Composable
private fun ToBot(
    bot: Bot?,
    fallbackName: String,
    note: String,
    words: String,
    sending: Boolean,
    failed: Boolean,
    full: (@Composable () -> Unit)? = null,
) {
    val faces = LocalBotFaces.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    val edge = if (failed) Theme[colors][danger] else Theme[colors][stroke]
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        Column(
            Modifier
                .clip(shape)
                .background(Theme[colors][surface], shape)
                .border(1.dp, edge, shape)
                .clickable(enabled = words.isNotBlank() || full != null, onClickLabel = if (open) "Show less" else "Show all") { open = !open }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                UnstyledIcon(Lucide.Send, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(12.dp))
                Text("To", style = Theme[typography][caption], color = Theme[colors][textTertiary])
                if (bot != null) BotAvatar(bot, faces.picture(bot), size = 16.dp)
                Text(
                    bot?.label ?: fallbackName,
                    style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.SemiBold),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(note, style = Theme[typography][caption], color = if (failed) Theme[colors][danger] else Theme[colors][textTertiary], maxLines = 1)
                if (sending) Spinner(Modifier.size(12.dp))
            }
            when {
                open && full != null -> Unit
                open -> if (failed) Monospace(words) else MarkdownText(words)
                words.isNotBlank() -> Text(words.toPreview(), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (open && full != null) Box(Modifier.fillMaxWidth()) { full() }
    }
}

private fun String.toPreview(): String = replace(Regex("""[*_`#>]+"""), "").replace(Regex("\\s+"), " ").trim()

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

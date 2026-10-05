package dev.hermeskotlin.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.previewLine
import dev.hermeskotlin.core.bots.showsPicture
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.SectionLabel
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.sessions.ListNotice
import dev.hermeskotlin.ui.sessions.ListSpinner
import dev.hermeskotlin.ui.sessions.MessageBanner

/**
 * The Bots side of the sidebar, Desktop's roster: one row per bot with its face, name, the latest line of
 * its chat and how long ago. Tapping a row opens the bot's one permanent chat.
 */
@Composable
fun BotsRoster(
    state: BotsUiState,
    avatars: Map<String, ByteArray>,
    /** The stored session open in the chat pane; its bot's row is highlighted. */
    selectedId: String?,
    nowSeconds: Double,
    onOpen: (Bot) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Room to scroll the last rows out from under the floating footer.
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
        ) {
            item(key = "label") { SectionLabel("Bots", Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp)) }
            when {
                state.loading && state.bots.isEmpty() -> item(key = "loading") { ListSpinner() }
                state.unsupported -> item(key = "unsupported") {
                    ListNotice("This gateway doesn't list bots yet. Bot Mode needs Hermes 0.20.3 or newer.")
                }
                state.error != null && state.bots.isEmpty() -> item(key = "error") {
                    ListNotice("Couldn't load the bots. ${state.error}", action = "Try again", onAction = onRetry)
                }
                state.bots.isEmpty() -> item(key = "empty") { ListNotice("No bots on this gateway yet.") }
            }
            items(state.bots, key = { it.name }) { bot ->
                val chat = bot.canonicalSession
                BotRow(
                    bot = bot,
                    picture = avatars[bot.name]?.takeIf { showsPicture(bot, it) },
                    selected = selectedId != null && chat != null && (selectedId == chat.id || selectedId == chat.openId),
                    unread = bot.name in state.unread,
                    working = bot.isWorking(nowSeconds),
                    opening = state.opening == bot.name,
                    onClick = { onOpen(bot) },
                )
            }
        }
        state.openError?.let { message ->
            MessageBanner(message, onDismiss = onDismissError, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
        }
    }
}

@Composable
private fun BotRow(
    bot: Bot,
    picture: ByteArray?,
    selected: Boolean,
    unread: Boolean,
    working: Boolean,
    opening: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val chat = bot.canonicalSession
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(shape)
            .then(if (selected) Modifier.background(Theme[colors][accentSoft], shape) else Modifier)
            .clickable(onClickLabel = "Open ${bot.label}'s chat", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            BotAvatar(bot, picture)
            if (working) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(10.dp)
                        .background(Theme[colors][success], CircleShape),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        bot.label,
                        style = Theme[typography][body].copy(fontSize = 15.sp, fontWeight = if (selected || unread) FontWeight.SemiBold else FontWeight.Medium),
                        color = Theme[colors][text],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (unread) Box(Modifier.size(7.dp).background(Theme[colors][accent], CircleShape))
                }
                when {
                    opening -> Spinner(Modifier.size(14.dp))
                    chat?.activityAt != null -> Text(relativeTime(chat.activityAt), style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
                }
            }
            val preview = bot.previewLine()
            Text(
                when {
                    working -> "Working…"
                    preview != null -> preview
                    else -> bot.description?.takeIf { it.isNotBlank() } ?: "Say hello"
                },
                style = Theme[typography][bodySmall],
                color = if (working) Theme[colors][success] else Theme[colors][textSecondary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

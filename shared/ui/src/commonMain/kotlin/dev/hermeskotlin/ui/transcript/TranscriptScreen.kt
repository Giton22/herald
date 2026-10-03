package dev.hermeskotlin.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Wrench
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusFull
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.EmptyState
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TranscriptScreen(
    gateway: SavedGateway,
    sessionId: String,
    title: String,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
    viewModel: TranscriptViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(sessionId) { viewModel.load(gateway, sessionId) }
    LaunchedEffect(state.sessionExpired) { if (state.sessionExpired) onSessionExpired() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(Lucide.ArrowLeft, contentDescription = "Back", onClick = onBack)
                Text(
                    title,
                    style = Theme[typography][heading],
                    color = Theme[colors][textColor],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                    state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load the transcript", state.error) {
                        Button(
                            "Try again",
                            onClick = { viewModel.load(gateway, sessionId, force = true) },
                            variant = ButtonVariant.Secondary,
                            leadingIcon = Lucide.RefreshCw,
                        )
                    }
                    state.items.isEmpty() -> EmptyState(Lucide.MessageSquare, "No messages", "This session has no visible messages yet.")
                    else -> Messages(state.items)
                }
            }
        }
    }
}

@Composable
private fun Messages(items: List<TranscriptItem>) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = items.size)
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomInset + 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is TranscriptItem.User -> UserBubble(item.text)
                is TranscriptItem.Assistant -> AssistantReply(item)
            }
        }
        item(key = "read-only") {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnstyledIcon(Lucide.Info, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
                Text("Read-only for now. Replying comes with live chat.", style = Theme[typography][caption], color = Theme[colors][textTertiary])
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        SelectionContainer {
            Text(
                text,
                style = Theme[typography][body],
                color = Theme[colors][textColor],
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .padding(start = 48.dp)
                    .background(Theme[colors][accentSoft], RoundedCornerShape(Theme[radii][radiusLarge]))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantReply(item: TranscriptItem.Assistant) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.tools.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Collapse repeats: "terminal ×3".
                item.tools.groupingBy { it }.eachCount().forEach { (name, count) -> ToolChip(if (count > 1) "$name ×$count" else name) }
            }
        }
        if (item.text.isNotEmpty()) {
            SelectionContainer {
                Text(item.text, style = Theme[typography][body], color = Theme[colors][textColor])
            }
        }
    }
}

@Composable
private fun ToolChip(name: String) {
    val shape = RoundedCornerShape(Theme[radii][radiusFull])
    Row(
        Modifier
            .border(1.dp, Theme[colors][stroke], shape)
            .background(Theme[colors][surface], shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.Wrench, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(12.dp))
        Text(name, style = Theme[typography][caption], color = Theme[colors][textSecondary])
    }
}


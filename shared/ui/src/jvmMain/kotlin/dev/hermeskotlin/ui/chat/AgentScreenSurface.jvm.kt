package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.hermeskotlin.core.chat.AgentScreen
import kotlinx.serialization.json.JsonElement

// The A2UI renderer is Android only; the desktop shows the call as a tool line.
internal actual val drawsAgentScreens: Boolean = false

@Composable
internal actual fun AgentScreenSurface(
    key: String,
    screen: AgentScreen,
    live: Boolean,
    takesTaps: Boolean,
    onAnswer: (surfaceId: String, result: JsonElement) -> Unit,
    onFail: (surfaceId: String, message: String) -> Unit,
    modifier: Modifier,
) = Unit

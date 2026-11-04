package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.chat.ChatState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Cache display is not a successful read. Wait for the gateway or an explicit error. */
internal suspend fun subscriptionHistory(
    states: StateFlow<ChatState>, timeoutMs: Long = 15_000,
): ChatState? {
    val state = withTimeoutOrNull(timeoutMs) {
        states.first { it.historyLoaded && (it.historyRead || it.historyError != null) }
    } ?: states.value
    return state.takeIf { it.historyLoaded && it.historyRead }
}

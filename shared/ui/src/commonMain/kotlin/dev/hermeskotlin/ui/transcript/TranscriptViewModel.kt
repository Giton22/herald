package dev.hermeskotlin.ui.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One rendered transcript entry: a user prompt, or an assistant reply with the tools it called. */
sealed interface TranscriptItem {
    val key: String

    data class User(override val key: String, val text: String) : TranscriptItem

    data class Assistant(override val key: String, val text: String, val tools: List<String>) : TranscriptItem
}

data class TranscriptUiState(
    val sessionId: String? = null,
    val loading: Boolean = true,
    val items: List<TranscriptItem> = emptyList(),
    val error: String? = null,
    val sessionExpired: Boolean = false,
)

/** Loads a stored transcript over REST. Read-only until live chat (`session.resume` + `prompt.submit`) lands. */
class TranscriptViewModel(private val api: SessionsApi) : ViewModel() {

    private val _state = MutableStateFlow(TranscriptUiState())
    val state: StateFlow<TranscriptUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun load(gateway: SavedGateway, sessionId: String, force: Boolean = false) {
        if (!force && _state.value.sessionId == sessionId && _state.value.error == null) return
        job?.cancel()
        _state.value = TranscriptUiState(sessionId = sessionId)
        job = viewModelScope.launch {
            val result = api.messages(gateway.gatewayUrl, sessionId)
            _state.value = when (result) {
                is ApiResult.Success -> TranscriptUiState(sessionId, loading = false, items = toItems(result.value.messages))
                else -> TranscriptUiState(
                    sessionId,
                    loading = false,
                    error = result.errorMessage,
                    sessionExpired = result == ApiResult.SessionExpired,
                )
            }
        }
    }
}

/**
 * Folds stored rows into chat items: tool results and system/hidden rows drop out, and consecutive
 * assistant rows (text, tool-call steps) merge into one reply that lists every tool it used.
 */
internal fun toItems(messages: List<SessionMessage>): List<TranscriptItem> {
    val items = mutableListOf<TranscriptItem>()
    messages.forEachIndexed { index, message ->
        if (message.isHidden) return@forEachIndexed
        val key = message.id?.toString() ?: "i$index"
        when (message.role) {
            "user" -> message.text.trim().takeIf { it.isNotEmpty() }?.let { items += TranscriptItem.User(key, it) }
            "assistant" -> {
                val text = message.text.trim()
                val tools = message.calledTools
                val previous = items.lastOrNull() as? TranscriptItem.Assistant
                if (previous != null) {
                    items[items.lastIndex] = previous.copy(
                        text = listOf(previous.text, text).filter { it.isNotEmpty() }.joinToString("\n\n"),
                        tools = previous.tools + tools,
                    )
                } else if (text.isNotEmpty() || tools.isNotEmpty()) {
                    items += TranscriptItem.Assistant(key, text, tools)
                }
            }
        }
    }
    return items
}

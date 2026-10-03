package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.LastChat
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Identifies what the chat screen shows: a stored session, or a new chat (`storedSessionId == null`). */
data class ChatTarget(val gateway: SavedGateway, val storedSessionId: String?, val title: String?, val nonce: Long = 0)

/**
 * Hosts one [ChatSession] at a time; opening another target replaces it. Remembers the open chat in
 * [LastChatStore] so the next launch returns to it: a stored session once it has messages, or nothing
 * while a new chat is still empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    private val lastChats: LastChatStore,
) : ViewModel() {

    val composer = TextFieldState()
    val connectionState: StateFlow<ConnectionState> = connection.state

    private var target: ChatTarget? = null
    private val session = MutableStateFlow<ChatSession?>(null)

    val state: StateFlow<ChatState> = session
        .flatMapLatest { it?.state ?: flowOf(ChatState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatState())

    init {
        viewModelScope.launch {
            state
                .map { chat -> chat.storedSessionId?.takeIf { chat.messages.isNotEmpty() }?.let { LastChat(it, chat.title) } }
                // Distinct before dropping nulls, so returning to the same chat after a new one saves it again.
                .distinctUntilChanged()
                .filterNotNull()
                .collect { last -> target?.let { lastChats.set(it.gateway.gatewayUrl, last) } }
        }
    }

    fun open(target: ChatTarget) {
        if (this.target == target) return
        this.target = target
        session.value?.stop()
        composer.clearText()
        if (target.storedSessionId == null) viewModelScope.launch { lastChats.set(target.gateway.gatewayUrl, null) }
        session.value = ChatSession(
            gateway = target.gateway.gatewayUrl,
            initialStoredId = target.storedSessionId,
            initialTitle = target.title,
            connection = connection,
            sessions = sessions,
            scope = viewModelScope,
        ).also { it.start() }
    }

    fun send() {
        val chat = session.value ?: return
        val text = composer.text.toString()
        if (text.isBlank()) return
        composer.clearText()
        viewModelScope.launch {
            // Give the text back if it never reached the gateway, so nothing typed is lost.
            if (!chat.send(text) && composer.text.isEmpty()) composer.setTextAndPlaceCursorAtEnd(text)
        }
    }

    fun interrupt() {
        val chat = session.value ?: return
        viewModelScope.launch { chat.interrupt() }
    }

    fun retry() = session.value?.retry()

    fun dismissError() = session.value?.dismissError()

    override fun onCleared() {
        session.value?.stop()
    }
}

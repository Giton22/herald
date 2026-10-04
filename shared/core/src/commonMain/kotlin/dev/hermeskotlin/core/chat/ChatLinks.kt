package dev.hermeskotlin.core.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A chat to open, asked for from outside the app's screens. */
data class ChatLink(val storedSessionId: String, val title: String?)

/**
 * Hands a chat to open from outside the UI, like a tapped notification, to the screens. Only the latest
 * one is kept; the app takes it once it's signed in.
 */
class ChatLinks {
    private val _pending = MutableStateFlow<ChatLink?>(null)
    val pending: StateFlow<ChatLink?> = _pending.asStateFlow()

    fun open(storedSessionId: String, title: String?) {
        _pending.value = ChatLink(storedSessionId, title)
    }

    /** Clears [link] once opened, unless a newer one came in meanwhile. */
    fun consume(link: ChatLink) = _pending.update { if (it == link) null else it }
}

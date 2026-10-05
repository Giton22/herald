package dev.hermeskotlin.core.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A chat to open, asked for from outside the app's screens. With [bot] it is that bot's permanent chat:
 * [storedSessionId] is where it was last seen, or null to look it up (a shortcut, a `hermes://bot/` link).
 */
data class ChatLink(val storedSessionId: String?, val title: String?, val bot: String? = null)

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

    /** Opens [bot]'s permanent chat, known to be [storedSessionId] when given. */
    fun openBot(bot: String, label: String?, storedSessionId: String? = null) {
        _pending.value = ChatLink(storedSessionId, label, bot = bot)
    }

    /** Clears [link] once opened, unless a newer one came in meanwhile. */
    fun consume(link: ChatLink) = _pending.update { if (it == link) null else it }
}

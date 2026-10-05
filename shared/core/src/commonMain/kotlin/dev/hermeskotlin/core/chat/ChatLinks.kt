package dev.hermeskotlin.core.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A chat to open, asked for from outside the app's screens: the stored session [storedSessionId], or with
 * none a new chat with [draft] in its composer. With [bot] it is that bot's permanent chat:
 * [storedSessionId] is where it was last seen, or null to look it up (a shortcut, a `hermes://bot/` link).
 */
data class ChatLink(
    val storedSessionId: String?,
    val title: String?,
    val draft: ComposeDraft? = null,
    val bot: String? = null,
)

/**
 * What a new chat opened from outside starts with: [text] in the composer and [attachments] in its tray
 * (shared from another app), or dictation running ([dictate], the voice shortcut). Nothing is sent until
 * the user sends it. [notice] is a sentence about anything that couldn't come along.
 */
class ComposeDraft(
    val text: String? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val notice: String? = null,
    val dictate: Boolean = false,
)

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

    /** Opens a new chat that starts with [draft]. */
    fun newChat(draft: ComposeDraft = ComposeDraft()) {
        _pending.value = ChatLink(storedSessionId = null, title = null, draft = draft)
    }

    /** Opens [bot]'s permanent chat with [draft] (shared from another app) added to its composer. */
    fun shareToBot(bot: String, draft: ComposeDraft) {
        _pending.value = ChatLink(storedSessionId = null, title = null, draft = draft, bot = bot)
    }

    /** Opens what a `hermes://` [link] names. */
    fun follow(link: AppLink) = when (link) {
        AppLink.NewChat -> newChat()
        AppLink.NewChatVoice -> newChat(ComposeDraft(dictate = true))
        is AppLink.Session -> open(link.id, title = null)
        is AppLink.Bot -> openBot(link.profile, label = null)
    }

    /** Clears [link] once opened, unless a newer one came in meanwhile. */
    fun consume(link: ChatLink) = _pending.update { if (it == link) null else it }
}

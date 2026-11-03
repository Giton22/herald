package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.desktop.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * One system notification, and the chat a click on it opens. [request] is what the agent waits on, when that's what
 * it tells: an approval can be answered from the notification itself.
 */
data class DesktopNotice(
    val title: String,
    val body: String,
    val sessionId: String?,
    val chatTitle: String?,
    val request: InputRequest? = null,
)

/** A notification's own words, in the system's language: read once, before the chat is followed. */
internal data class NoticeWords(
    val replyFailed: String,
    val replied: String,
    val replyIn: String,
    val approval: String,
    val question: String,
    val needsYou: String,
) {
    companion object {
        suspend fun load() = NoticeWords(
            replyFailed = getString(Res.string.notice_reply_failed),
            replied = getString(Res.string.notice_replied),
            replyIn = getString(Res.string.notice_reply_in),
            approval = getString(Res.string.notice_approval),
            question = getString(Res.string.notice_question),
            needsYou = getString(Res.string.notice_needs_you),
        )
    }
}

/**
 * The open chat's moments worth a system notification, as Hermes Desktop has them: a question or an approval the
 * agent waits on, and a reply finishing or failing. Only while the window isn't the one in use, since the chat says
 * it all when it is. [notified] keeps the requests already told, so each is told once.
 */
internal fun noticesFor(
    previous: ChatState?,
    state: ChatState,
    focused: Boolean,
    prefs: AppSettings,
    notified: MutableSet<String>,
    words: NoticeWords,
): List<DesktopNotice> {
    val notices = mutableListOf<DesktopNotice>()
    val chat = state.title?.takeIf { it.isNotBlank() }
    // A request answered elsewhere, or seen in the window, is done with.
    notified.retainAll(state.inputRequests.mapTo(HashSet()) { it.id })
    if (focused) {
        notified.addAll(state.inputRequests.map { it.id })
    } else if (prefs.notifyRequests) {
        for (request in state.inputRequests) {
            if (notified.add(request.id)) notices += DesktopNotice(requestTitle(request, chat, words), requestBody(request), state.storedSessionId, chat, request)
        }
    }
    if (previous?.running == true && !state.running && !focused && prefs.notifyReplies) {
        val reply = state.messages.lastOrNull() as? ChatMessage.Assistant
        if (reply != null && reply.outcome != TurnOutcome.Interrupted) {
            val failed = reply.outcome == TurnOutcome.Error
            val text = (if (failed) reply.error ?: reply.text else reply.text).trim()
            notices += DesktopNotice(
                title = if (failed) words.replyFailed else chat ?: words.replied,
                body = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(BODY_LIMIT) ?: words.replyIn,
                sessionId = state.storedSessionId,
                chatTitle = chat,
            )
        }
    }
    return notices
}

private fun requestTitle(request: InputRequest, chat: String?, words: NoticeWords): String {
    val what = when (request) {
        is InputRequest.Approval -> words.approval
        is InputRequest.Clarify -> words.question
        else -> words.needsYou
    }
    return if (chat == null) what else "$what · $chat"
}

private fun requestBody(request: InputRequest): String = when (request) {
    is InputRequest.Approval -> request.description.ifBlank { request.command }
    is InputRequest.Clarify -> request.questions.firstOrNull()?.question.orEmpty()
    is InputRequest.Secret -> request.prompt
    is InputRequest.VaultSaveLogin -> request.title
    else -> ""
}.lineSequence().firstOrNull { it.isNotBlank() }?.take(BODY_LIMIT).orEmpty()

private const val BODY_LIMIT = 200

/** Follows whichever chat is open in [host] and hands what it should tell to [post]. */
class DesktopNotifier(
    private val host: ChatHost,
    private val settings: SettingsStore,
    private val focused: StateFlow<Boolean>,
    private val post: (DesktopNotice) -> Unit,
) {
    fun start(scope: CoroutineScope) {
        scope.launch { host.session.collectLatest { session -> if (session != null) follow(session) } }
    }

    private suspend fun follow(session: ChatSession) {
        val notified = mutableSetOf<String>()
        var previous: ChatState? = null
        val words = NoticeWords.load()
        combine(session.state, focused, settings.settings) { state, focused, prefs -> Triple(state, focused, prefs ?: AppSettings()) }
            .collect { (state, focused, prefs) ->
                noticesFor(previous, state, focused, prefs, notified, words).forEach(post)
                previous = state
            }
    }
}

package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.settings.RunningSend
import dev.hermeskotlin.core.slash.SlashSuggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

private val DefaultRunningSend: StateFlow<RunningSend> = MutableStateFlow(RunningSend.Steer).asStateFlow()

/**
 * What the chat's layout ([ChatView]) asks of whatever runs the chat: [ChatViewModel] in the app,
 * sample data in previews.
 */
interface ChatActions {
    /** What's being typed. */
    val composer: TextFieldState

    /**
     * Sends the composer. Mid-turn it goes the way [mode] says (steered in, queued, or sent after a stop),
     * or as [runningSend] says when no mode was picked for this message.
     */
    fun send(mode: RunningSend? = null)

    /** What Send does while a reply runs, from Settings. */
    val runningSend: StateFlow<RunningSend> get() = DefaultRunningSend

    fun interrupt()

    fun removeAttachment(id: String)

    /** Adds a comment on [anchor]'s selection to the next send; returns its id. */
    fun addComment(source: CommentSource, anchor: SelectionAnchor): Long

    fun removeComment(id: Long)

    /** Asks the agent to explain the selection: at once when nothing else waits to go out, else as a comment. */
    fun explain(source: CommentSource, anchor: SelectionAnchor)

    /** Starts a `/btw` side question about the selection in the composer. */
    fun askAside(source: CommentSource, anchor: SelectionAnchor)

    fun answer(request: InputRequest, result: JsonObject)

    fun pickSuggestion(suggestion: SlashSuggestion)

    /** The bytes behind a picture or file in the chat, or null when it can't be had. */
    suspend fun loadMedia(source: String): ByteArray?

    fun stopSubagent(subagentId: String)

    /**
     * `/undo`: takes the last prompt and its reply off the chat and puts the prompt back in the composer.
     * Does nothing unless prompt [key] is still the last one and the chat [can change][canChangeChat].
     */
    fun editLastPrompt(key: String)

    /** Copies the chat up to message [key] into a new chat (`session.branch`) and opens it, if it [can change][canChangeChat]. */
    fun branchFrom(key: String)

    /** Drops reply [key] and everything after it and sends the prompt it answered again, if the chat [can change][canChangeChat]. */
    fun regenerate(key: String)

    /**
     * Puts prompt [key] in the composer to change it; the next send replaces it and everything after it.
     * What was being typed waits and comes back when the edit is sent or [cancelled][cancelEdit].
     */
    fun startEdit(key: String)

    fun cancelEdit()

    /** Looks in the transcript again for a prompt whose delivery is unknown. */
    fun checkDelivery(key: String)

    /** Sends an unsettled prompt again (the screen warns first when it may already have arrived). */
    fun resend(key: String)

    /** Takes an unsettled prompt back into the composer to change it. */
    fun editMessage(key: String)

    fun retry()

    /** Loads the page of the conversation before what's shown, as the reader scrolls to the top. */
    fun loadOlder() {}

    fun dismissError()

    /** Hides the gateway notice with [key] (its ×, or a timed one running out). */
    fun dismissNotice(key: String) {}

    fun dismissAttachmentError()

    fun skipSpeech()

    fun stopVoiceChat()

    fun dismissVoiceChatError()

    fun dismissDictationError()
}

/**
 * Whether editing the last prompt or branching may run now: on a stored chat, while [connected], with no task
 * writing to it and no command still waiting on its answer (a second `/undo` would take another turn).
 * A `/btw` side question doesn't count; it leaves the transcript alone.
 */
internal fun ChatState.canChangeChat(connected: Boolean): Boolean =
    storedSessionId != null && !running && connected &&
        messages.none { it is ChatMessage.Command && it.running && it.taskId == null }

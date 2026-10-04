package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.slash.SlashSuggestion
import kotlinx.serialization.json.JsonObject

/**
 * What the chat's layout ([ChatView]) asks of whatever runs the chat: [ChatViewModel] in the app,
 * sample data in previews.
 */
interface ChatActions {
    /** What's being typed. */
    val composer: TextFieldState

    /** Sends the composer; mid-turn it corrects the running turn, unless [queue] holds it for the next one. */
    fun send(queue: Boolean = false)

    fun interrupt()

    fun removeAttachment(id: String)

    fun answer(request: InputRequest, result: JsonObject)

    fun pickSuggestion(suggestion: SlashSuggestion)

    /** The bytes behind a picture or file in the chat, or null when it can't be had. */
    suspend fun loadMedia(source: String): ByteArray?

    fun stopSubagent(subagentId: String)

    /** `/undo`: takes the last prompt and its reply off the chat and puts the prompt back in the composer. */
    fun editLastPrompt()

    /** Copies the chat up to message [key] into a new chat (`session.branch`) and opens it. */
    fun branchFrom(key: String)

    /** Looks in the transcript again for a prompt whose delivery is unknown. */
    fun checkDelivery(key: String)

    /** Sends an unsettled prompt again (the screen warns first when it may already have arrived). */
    fun resend(key: String)

    /** Takes an unsettled prompt back into the composer to change it. */
    fun editMessage(key: String)

    fun retry()

    fun dismissError()

    fun dismissAttachmentError()

    fun skipSpeech()

    fun stopVoiceChat()

    fun dismissVoiceChatError()

    fun dismissDictationError()
}

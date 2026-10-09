package dev.hermeskotlin.ui.preview

import androidx.compose.foundation.text.input.TextFieldState
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.ui.assistant.AssistantActions
import dev.hermeskotlin.ui.assistant.AssistantCardState
import dev.hermeskotlin.ui.assistant.AssistantPhase
import dev.hermeskotlin.ui.assistant.ScreenCapture
import dev.hermeskotlin.ui.assistant.ScreenItem
import kotlinx.serialization.json.JsonObject

/** The assistant panel's card, called up over a calendar: offering the screen, and after a question about it. */
internal object AssistantSamples {
    private val calendar = ScreenCapture(
        app = "Calendar",
        items = listOf("Thursday", "Dentist 09:30", "Team sync 11:00", "Pick up the bike 17:45").map { ScreenItem(it) },
    )

    /** Just called up: the screen offered, the microphone listening. */
    val offer = AssistantCardState(
        phase = AssistantPhase.Ready,
        connected = true,
        screen = calendar,
        dictation = dev.hermeskotlin.ui.voice.DictationState(recording = true, level = 0.5f),
    )

    /** A question about the screen, answered. */
    val answered = AssistantCardState(
        phase = AssistantPhase.Ready,
        connected = true,
        screen = calendar,
        chat = ChatState(
            messages = listOf(
                ChatMessage.User("u1", "Do I have time for a run before the team sync?"),
                ChatMessage.Assistant(
                    key = "a1",
                    text = "Yes, about **50 minutes**. The dentist is at 09:30 and should be done by 10:00; " +
                        "the team sync starts at 11:00.\n\nA 30-minute run leaves you time to shower and get there.",
                ),
            ),
        ),
    )
}

/** Actions that do nothing, for the previews. */
internal object PreviewAssistantActions : AssistantActions {
    override val composer = TextFieldState()
    override fun send() = Unit
    override fun stop() = Unit
    override fun answer(request: InputRequest, result: JsonObject) = Unit
    override fun toggleDictation() = Unit
    override fun setIncludeScreen(include: Boolean) = Unit
    override fun startCircling() = Unit
    override fun dropCircled() = Unit
}

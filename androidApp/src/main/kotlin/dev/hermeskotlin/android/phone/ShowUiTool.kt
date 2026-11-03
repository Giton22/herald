package dev.hermeskotlin.android.phone

import android.content.Context
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.NOT_DRAWN
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.phone.AgentScreens
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.ui.chat.agentScreenProblem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A screen the agent draws in the chat (experiment). The chat draws it from the call's own arguments; this checks
 * Kyllene can draw it and, when the agent waits, hands back what the user tapped there, in the chat that called.
 */
class ShowUiTool(private val context: Context, private val screens: AgentScreens, private val chats: ChatTargets) {

    suspend fun run(args: JsonObject, session: String): JsonElement {
        val screen = AgentScreen.from(SHOW_UI_TOOL, args)
            ?: throw PhoneToolException(
                "messages must be a list of 1 to ${AgentScreen.MAX_MESSAGES} A2UI messages (JSON objects), each naming its surfaceId.",
            )
        // The chat's stored id, as the chat screen knows it; the gateway may name it by its live id.
        val chat = chats.of(session)?.storedSessionId ?: session.takeIf { it.isNotBlank() }
        // An update is checked on top of the screen it changes, as the chat drew it.
        val earlier = screens.earlier(screen, chat)
        val problem = if (earlier == null) {
            UNKNOWN_SURFACE
        } else {
            agentScreenProblem(context, screen.copy(messages = earlier + screen.messages))
        }
        // Said so the chat knows the screen stayed as it was.
        problem?.let { throw PhoneToolException(NOT_DRAWN + it) }
        screens.record(screen, chat)
        if (!screen.wait) return buildJsonObject { put("shown", true) }
        return screens.await(screen, chat)
    }

    private companion object {
        const val UNKNOWN_SURFACE = "A message updates a surface this chat doesn't show: start a new screen with " +
            "createSurface, or use the surfaceId of a screen you showed in this chat."
    }
}

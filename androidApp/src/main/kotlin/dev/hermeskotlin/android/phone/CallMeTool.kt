package dev.hermeskotlin.android.phone

import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.phone.CallOutcome
import dev.hermeskotlin.core.phone.CallReason
import dev.hermeskotlin.core.phone.clockTime
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `phone_call_me`: the agent rings this phone like an incoming call (and Kyllene on the watch), and hears how it
 * ended. It rings only the user: there's no number to give. Answer puts Hermes on the line in the chat the agent
 * called from, which opens with the reason.
 */
internal class CallMeTool(private val notifications: ChatNotifications, private val chats: ChatTargets) {

    suspend fun run(args: JsonObject, session: String): JsonObject {
        val reason = args.string("reason")?.trim()?.takeIf { it.isNotEmpty() } ?: throw PhoneToolException("reason is needed.")
        val details = args.string("details")?.trim()?.takeIf { it.isNotEmpty() }
        // The chat on this phone it came from; else the gateway's own id for it (a scheduled job's), which the call
        // resumes.
        val target = chats.of(session)
        val chat = target?.storedSessionId ?: session.takeIf { it.isNotBlank() }
        val outcome = notifications.ringForAgent(chat, target?.title, CallReason(reason, details))
            ?: throw PhoneToolException("Kyllene may not post notifications on this phone, so it can't ring.")
        return buildJsonObject {
            put("status", outcome.status)
            // When it may ring again, for the agent to send a reminder for then.
            if (outcome == CallOutcome.Quiet) notifications.quietUntil()?.let { put("until", clockTime(it)) }
        }
    }
}

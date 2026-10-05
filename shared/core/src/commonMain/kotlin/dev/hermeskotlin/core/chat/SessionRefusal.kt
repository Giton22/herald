package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.RpcException

/**
 * Why the gateway wouldn't start a turn in a chat: `prompt.submit` answered 4090 with a reason in its
 * data (tui_gateway/methods_prompt.py, hermes_cli/active_sessions.py). Nothing ran and nothing was written,
 * so the prompt can simply go again later. Not a failure of the chat, so it's said as a state, not an error.
 */
enum class SessionRefusal(val title: String, val detail: String) {
    /** Another client (Hermes Desktop, the CLI, another phone) holds the chat's turn lease. */
    OpenElsewhere(
        "Open on another device",
        "Another app, like Hermes Desktop, is using this chat right now. Send again once it's done there.",
    ),

    /** The gateway is running as many chats as it allows at once. */
    TooManyChats(
        "Too many chats running",
        "The gateway is running as many chats as it allows at once. Send again when one of them finishes.",
    ),

    /** The gateway couldn't check who holds the chat. */
    Unavailable(
        "Couldn't claim this chat",
        "The gateway couldn't check whether another app is using this chat. Try again in a moment.",
    ),
    ;

    companion object {
        private const val CODE = 4090

        /** The refusal [error] stands for, or null when it's some other failure. */
        fun of(error: Throwable): SessionRefusal? {
            val rpc = error as? RpcException ?: return null
            if (rpc.code != CODE) return null
            return when (rpc.reason) {
                "SESSION_NOT_OWNED" -> OpenElsewhere
                "MAX_CONCURRENT_SESSIONS" -> TooManyChats
                "SESSION_COORDINATION_UNAVAILABLE" -> Unavailable
                else -> null
            }
        }
    }
}

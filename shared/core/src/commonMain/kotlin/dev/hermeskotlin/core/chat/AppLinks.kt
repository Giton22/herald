package dev.hermeskotlin.core.chat

/** A `hermes://` link the app opens: from a launcher shortcut, another app, or a web page. */
sealed interface AppLink {
    /** `hermes://new-chat`: a new, empty chat. */
    data object NewChat : AppLink

    /** `hermes://new-chat-voice`: a new chat that starts dictating into its composer. */
    data object NewChatVoice : AppLink

    /** `hermes://session/<id>`: a stored chat. */
    data class Session(val id: String) : AppLink

    /** `hermes://bot/<profile>`: that bot's permanent chat, the link Hermes Desktop uses for a bot. */
    data class Bot(val profile: String) : AppLink

    companion object {
        const val SCHEME = "hermes"

        /** The link [uri] names, or null when it isn't one of ours. */
        fun parse(uri: String): AppLink? {
            val scheme = uri.substringBefore(':', "")
            if (!scheme.equals(SCHEME, ignoreCase = true)) return null
            val rest = uri.substringAfter(':').removePrefix("//").substringBefore('?').substringBefore('#')
            val host = rest.substringBefore('/').lowercase()
            val segments = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() }.map(::percentDecode)
            return when (host) {
                "new-chat" -> NewChat.takeIf { segments.isEmpty() }
                "new-chat-voice" -> NewChatVoice.takeIf { segments.isEmpty() }
                "session" -> segments.singleOrNull()?.takeIf { it.isNotBlank() }?.let(::Session)
                "bot" -> segments.singleOrNull()?.takeIf { it.isNotBlank() }?.let(::Bot)
                else -> null
            }
        }

        /** Decodes `%XX` escapes as UTF-8; a malformed escape is kept as written. */
        internal fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val bytes = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                val hex = if (text[i] == '%' && i + 2 <= text.lastIndex) text.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    bytes += hex.toByte()
                    i += 3
                } else {
                    // Up to the next escape as is, so characters beyond one UTF-16 unit stay whole.
                    val end = text.indexOf('%', i + 1).let { if (it < 0) text.length else it }
                    text.substring(i, end).encodeToByteArray().forEach { bytes += it }
                    i = end
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}

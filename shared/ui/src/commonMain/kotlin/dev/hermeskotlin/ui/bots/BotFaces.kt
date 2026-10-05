package dev.hermeskotlin.ui.bots

import androidx.compose.runtime.staticCompositionLocalOf
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.showsPicture

/** The gateway's bots and their pictures, for drawing a bot wherever it's named (e.g. its messages to another). */
class BotFaces(private val bots: List<Bot> = emptyList(), private val pictures: Map<String, ByteArray> = emptyMap()) {

    /** The bot a message names, by `@handle` or name; `hermes` is the primary bot. */
    fun find(handleOrName: String?): Bot? {
        val key = handleOrName?.trim()?.removePrefix("@")?.substringBefore('@')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val profile = if (key == "hermes") Bot.DEFAULT else key
        return bots.firstOrNull { it.name.lowercase() == profile }
            ?: bots.firstOrNull { it.label.lowercase() == key || it.label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') == key }
    }

    fun picture(bot: Bot): ByteArray? = pictures[bot.name]?.takeIf { showsPicture(bot, it) }
}

val LocalBotFaces = staticCompositionLocalOf { BotFaces() }

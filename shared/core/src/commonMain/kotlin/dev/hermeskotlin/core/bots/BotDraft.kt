package dev.hermeskotlin.core.bots

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A bot about to be made: its profile id, the title and description it introduces itself with, its
 * face, and optionally a SOUL of its own (else Desktop's generated persona).
 */
data class BotDraft(
    val profile: String,
    val title: String = "",
    val description: String = "",
    val shape: BotShape? = null,
    /** `#rrggbb`; null keeps the hue from the name. */
    val color: String? = null,
    val soul: String = "",
) {
    /** The `hermes-bots` fields this draft sets, as Desktop writes them. */
    fun look(): Map<String, JsonElement?> = buildMap {
        put("title", JsonPrimitive(title.trim()))
        put("imageKind", JsonPrimitive("shape"))
        shape?.let { put("shape", JsonPrimitive(it.name.lowercase())) }
        color?.let { put("color", JsonPrimitive(it)) }
        if (shape != null || color != null) put("custom", JsonPrimitive(true))
    }

    /** Why it can't be made as it is, or null when it can. */
    fun problem(taken: Set<String>): String? = when {
        profile.isEmpty() -> "Give it a name."
        !PROFILE_ID.matches(profile) -> "Names make an id of letters, digits, - and _."
        profile in taken -> "A bot called \"$profile\" already exists."
        else -> null
    }
}

data class BotCreated(val profile: String, val readyToChat: Boolean, val problem: String?)

/** A bot's own words about itself, read for its editor. */
data class BotDetails(val soul: String, val description: String)

/** `#rrggbb` for a packed `0xRRGGBB`. */
fun cssHex(color: Int): String = "#" + (color and 0xFFFFFF).toString(16).padStart(6, '0')

/** Desktop's picker swatches (lib/profile-color.ts `PROFILE_SWATCHES`): twelve hues at 68% / 58%. */
val BOT_SWATCHES: List<Int> = (0 until 12).map { parseCssColor("hsl(${it * 30} 68% 58%)")!! }

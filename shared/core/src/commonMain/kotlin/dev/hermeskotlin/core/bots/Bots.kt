package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One Bot of Hermes' Bot Mode: a profile seen as a named agent with one permanent chat. A `profiles.list`
 * row over the socket (tui_gateway/methods_profiles.py), which carries what `GET /api/profiles` doesn't:
 * the chat previews and the look Desktop keeps in the profile's `ui_meta`. Times are epoch seconds.
 */
@Serializable
data class Bot(
    val name: String,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    val model: String? = null,
    val provider: String? = null,
    /** The newest conversation on the profile, whatever it is. */
    @SerialName("last_session") val lastSession: BotSession? = null,
    /** The newest worker (kanban, tool) session; workers heartbeat at least every minute while running. */
    @SerialName("worker_session") val workerSession: BotSession? = null,
    /** The Bot Chat, resolved by the gateway on every listing; null until there is one. */
    @SerialName("canonical_session") val canonicalSession: BotSession? = null,
    @SerialName("ui_meta") val uiMeta: JsonObject? = null,
    @SerialName("has_avatar") val hasAvatar: Boolean = false,
) {
    /** Desktop's look and title for this bot (`ui_meta["hermes-bots"]`); every field may be missing. */
    val meta: BotMeta get() = BotMeta.of(uiMeta?.get(BotMeta.KEY))

    /** Desktop's name for the bot: its Bot Mode title, the profile's display name, "Hermes" for the default, else the id. */
    val label: String
        get() = meta.title
            ?: displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (name.equals(DEFAULT, ignoreCase = true)) "Hermes" else name.replace(Regex("[-_]+"), " ").trim().titleCase()

    /** Working on something in the background: a worker session heartbeat within the last two minutes. */
    fun isWorking(nowSeconds: Double): Boolean =
        workerSession?.lastActive?.let { nowSeconds - it < WORKING_WINDOW_SECONDS } == true

    companion object {
        const val DEFAULT = "default"
        private const val WORKING_WINDOW_SECONDS = 120.0
    }
}

/** A session summary on a roster row. For the Bot Chat, [id] names it and [resolvedId] is where it lives now. */
@Serializable
data class BotSession(
    val id: String? = null,
    /** The newest session of the chat's compression lineage: after `/compress` the live chat moves there. */
    @SerialName("resolved_id") val resolvedId: String? = null,
    val title: String? = null,
    /** The newest message, whitespace collapsed and cut at 80 characters; may still carry Markdown. */
    val preview: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
) {
    /** The stored session to open: the live end of the chat. */
    val openId: String? get() = resolvedId?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() }

    val activityAt: Double? get() = lastActive ?: startedAt
}

/** What `profiles.list` returned: the bots, and whether the backend teaches them to message each other. */
data class BotRoster(val bots: List<Bot>, val teammateProtocol: Boolean)

/**
 * Desktop's per-bot presentation (`ui_meta["hermes-bots"]` in profile.yaml), written by its Bot Mode plugin
 * without a schema. Read leniently: a field of an unexpected type is simply missing.
 */
data class BotMeta(
    val title: String? = null,
    val shape: String? = null,
    /** `#rrggbb` or `hsl(h s% l%)`, as Desktop stores it. */
    val color: String? = null,
    /** The user changed the look, so the default bot stops wearing the stock violet. */
    val custom: Boolean = false,
    val hidden: Boolean = false,
    /** `photo` when the avatar asset is a real picture, `shape` when the face is drawn. */
    val imageKind: String? = null,
) {
    companion object {
        const val KEY = "hermes-bots"

        fun of(element: JsonElement?): BotMeta {
            val obj = element as? JsonObject ?: return BotMeta()
            fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            fun flag(key: String) = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
            return BotMeta(title = text("title"), shape = text("shape"), color = text("color"), custom = flag("custom"), hidden = flag("hidden"), imageKind = text("imageKind"))
        }
    }
}

/** Reads a `profiles.list` reply; rows that aren't profiles are skipped rather than failing the roster. */
fun parseBotRoster(result: JsonElement?): BotRoster {
    val obj = result as? JsonObject ?: return BotRoster(emptyList(), teammateProtocol = false)
    val rows = obj["profiles"] as? kotlinx.serialization.json.JsonArray ?: emptyList()
    val bots = rows.mapNotNull { row -> runCatching { HermesJson.decodeFromJsonElement(Bot.serializer(), row.jsonObject) }.getOrNull() }
    val protocol = (obj["bot_mode_protocol"] as? JsonPrimitive)?.booleanOrNull == true
    return BotRoster(bots, protocol)
}

/**
 * Desktop's roster order: the default bot first, then the rest by their Bot Chat's latest activity, bots
 * never talked to last by name. Hidden bots stay out.
 */
fun List<Bot>.forRoster(): List<Bot> = filterNot { it.meta.hidden }
    .sortedWith(
        compareByDescending<Bot> { it.name == Bot.DEFAULT }
            .thenByDescending { it.canonicalSession?.activityAt ?: it.lastSession?.activityAt ?: 0.0 }
            .thenBy { it.label.lowercase() },
    )

/** The Bot Chat's latest line as a roster row shows it: Markdown marks taken out, one line (labels.ts `stripPreviewMarkdown`). */
fun Bot.previewLine(): String? = (canonicalSession?.preview ?: return null).let { text ->
    text.replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`\\n]*)`"), "$1")
        .replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")
        .replace(Regex("~~(.*?)~~"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotEmpty() }
}

private fun String.titleCase(): String = split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

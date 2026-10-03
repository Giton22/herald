package dev.hermeskotlin.core.slash

import dev.hermeskotlin.core.chat.asObjectList
import dev.hermeskotlin.core.chat.string
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A typed `/name arg…` line, split the way the gateway splits it (apps/shared/src/slash.ts). */
data class SlashCommand(
    /** Lower-cased, without the slash; empty for a bare `/`. */
    val name: String,
    /** The argument with outer whitespace trimmed; its interior (newlines included) kept as typed. */
    val arg: String,
) {
    /** The line as the gateway's `slash.exec` takes it: no leading slash. */
    val line: String get() = if (arg.isEmpty()) name else "$name $arg"

    companion object {
        /** `/` at the start, then a bare name and whitespace or the end. `/usr/local` and `run /x` are prose. */
        private val COMMAND = Regex("""^/[^\s/]*(?:\s|$)""")

        fun looksLikeCommand(text: String): Boolean = COMMAND.containsMatchIn(text)

        /** The command in [text], or null when [text] is ordinary prose. */
        fun parse(text: String): SlashCommand? {
            if (!looksLikeCommand(text)) return null
            val body = text.trimStart('/')
            val name = body.takeWhile { !it.isWhitespace() }
            return SlashCommand(name.lowercase(), body.drop(name.length).trim())
        }
    }
}

enum class SlashKind { Command, Skill, Option }

/** One row of the `/` list. Picking it puts [text] in the composer. */
data class SlashSuggestion(
    val text: String,
    val label: String,
    val description: String = "",
    val kind: SlashKind = SlashKind.Command,
    /** Section header for the bare `/` list ("Session", "Skills"…); null in search results. */
    val group: String? = null,
)

/**
 * `commands.catalog`: every command and skill the gateway offers, by category, plus how each built-in
 * fits a graphical client ([surfaces]: null to offer it, `hidden`, or why it can't run here).
 */
data class SlashCatalog(
    val suggestions: List<SlashSuggestion> = emptyList(),
    val surfaces: Map<String, String?> = emptyMap(),
    /** Alias → canonical command, both with the slash. */
    val canon: Map<String, String> = emptyMap(),
) {
    fun canonical(name: String): String {
        val key = "/${name.lowercase()}"
        return (canon[key] ?: key).removePrefix("/")
    }

    /** The gateway's note for a built-in ([surfaces]); null when it is offered or unknown. */
    fun surface(name: String): String? = surfaces["/${canonical(name)}"]

    companion object {
        fun parse(result: JsonObject?): SlashCatalog {
            if (result == null) return SlashCatalog()
            val surfaces = (result["commands"] as? JsonObject).orEmpty().mapValues { (_, meta) -> (meta as? JsonObject).string("desktop") }
            val canon = (result["canon"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k.withSlash() to it.withSlash() }
            }.toMap()
            val sections = result["categories"].asObjectList().mapNotNull { section ->
                val name = section.string("name") ?: return@mapNotNull null
                name to section["pairs"].pairs()
            }.ifEmpty { listOf("" to result["pairs"].pairs()) }
            val rows = sections.flatMap { (group, pairs) ->
                pairs.map { (command, meta) -> SlashSuggestion(command.withSlash(), command.withSlash(), meta, group = group.ifEmpty { null }) }
            }
            // Skills only come in the flat list; the categories hold the built-ins.
            val categorized = rows.mapTo(HashSet()) { it.text.lowercase() }
            val skills = result["pairs"].pairs()
                .filter { (command, _) -> command.withSlash().lowercase() !in categorized }
                .map { (command, meta) -> SlashSuggestion(command.withSlash(), command.withSlash(), meta, SlashKind.Skill, group = "Skills") }
            return SlashCatalog(rows + skills, surfaces, canon)
        }
    }
}

/** What a command typed in the composer turns into on this client. */
sealed interface SlashRoute {
    data object NewChat : SlashRoute
    data object Stop : SlashRoute
    data object PickModel : SlashRoute
    data object BrowseSessions : SlashRoute
    data object Help : SlashRoute
    data object Compress : SlashRoute
    data object Status : SlashRoute
    data object Aside : SlashRoute
    data object Reasoning : SlashRoute
    data object Yolo : SlashRoute
    data object Title : SlashRoute
    data object Branch : SlashRoute
    data object Profile : SlashRoute
    data object Handoff : SlashRoute
    data object Skin : SlashRoute
    data object Journey : SlashRoute
    data object Pet : SlashRoute

    /** Known, but there is nothing on this client to run it with. */
    data class Unavailable(val message: String) : SlashRoute

    /** Runs on the gateway (`slash.exec`, then `command.dispatch`). */
    data object Gateway : SlashRoute

    companion object {
        /** The client-side half of Desktop's command table (desktop-slash-commands.ts), trimmed to what this app has. */
        fun of(name: String, catalog: SlashCatalog?): SlashRoute {
            val canonical = LOCAL_ALIASES[name] ?: catalog?.canonical(name) ?: name
            LOCAL[canonical]?.let { return it }
            DESKTOP_ONLY[canonical]?.let { return Unavailable(it.replace("%s", "/$canonical")) }
            return when (catalog?.surface(canonical)) {
                "terminal" -> Unavailable("/$canonical only works in the terminal.")
                "messaging" -> Unavailable("/$canonical is for messaging platforms.")
                "composer-voice" -> Unavailable("Voice isn't available in this app yet.")
                "settings", "advanced" -> Unavailable("/$canonical isn't available in this app.")
                else -> Gateway
            }
        }

        /** Commands Desktop answers itself and the list leaves out here, because nothing would happen. */
        fun hidden(name: String, catalog: SlashCatalog?): Boolean {
            val canonical = LOCAL_ALIASES[name] ?: catalog?.canonical(name) ?: name
            if (canonical == "model") return false
            return of(canonical, catalog) is Unavailable || catalog?.surface(canonical) == "hidden"
        }

        private val LOCAL = mapOf(
            "new" to NewChat,
            "stop" to Stop,
            "model" to PickModel,
            "resume" to BrowseSessions,
            "help" to Help,
            "compress" to Compress,
            "status" to Status,
            "btw" to Aside,
            "reasoning" to Reasoning,
            "yolo" to Yolo,
            "title" to Title,
            "branch" to Branch,
            "profile" to Profile,
            "handoff" to Handoff,
            "skin" to Skin,
            "journey" to Journey,
            "pet" to Pet,
        )

        private val LOCAL_ALIASES = mapOf(
            "reset" to "new",
            "sessions" to "resume",
            "switch" to "resume",
            "commands" to "help",
            "compact" to "compress",
            "fork" to "branch",
            "learning" to "journey",
            "memory-graph" to "journey",
            "pets" to "pet",
        )

        /**
         * Commands Desktop answers inside its own window. The gateway offers them to graphical clients,
         * but run there they'd act on a background copy of the agent, not this chat.
         */
        private val DESKTOP_ONLY = buildMap {
            put("wake", "%s listens on the gateway computer's microphone, a Desktop feature.")
            put("browser", "%s connects a browser on the gateway computer, so it only works from Desktop there.")
            listOf("hatch", "generate-pet").forEach { put(it, "Hatching a new pet takes Desktop's generator for now; adopt one with /pet.") }
            listOf("density", "details", "logs", "mouse").forEach { put(it, "%s only works in the terminal.") }
        }
    }
}

/** What the gateway made of a command run through [SlashApi] (`command.dispatch`'s directive). */
sealed interface SlashResult {
    /** Text to show under the command; [warning] when a side effect didn't carry over. */
    data class Output(val text: String, val warning: String? = null) : SlashResult

    /** Run `/target arg` instead. */
    data class Alias(val target: String) : SlashResult

    /**
     * Send [message] as a prompt (a skill loads its body this way). The bubble shows [display], never
     * [message], which is model-facing scaffolding. [notice] is a line to show first.
     */
    data class Send(val message: String, val display: String?, val notice: String?) : SlashResult

    /** Put [message] in the composer for editing (`/undo` hands the last prompt back this way). */
    data class Prefill(val message: String, val notice: String?) : SlashResult

    companion object {
        /** Reads a `slash.exec` or `command.dispatch` result; null when it is neither. */
        fun parse(result: JsonObject?): SlashResult? {
            if (result == null) return null
            val message = result.string("message")
            return when (result.string("type")) {
                "exec", "plugin" -> Output(result.string("output") ?: "")
                "alias" -> result.string("target")?.let { Alias(it.removePrefix("/")) }
                "send" -> message?.let { Send(it, result.string("display"), result.string("notice")) }
                "skill" -> message?.let { Send(it, result.string("display"), null) }
                "prefill" -> message?.let { Prefill(it, result.string("notice")) }
                null -> result.string("output")?.let { Output(it, result.string("warning")?.takeIf { w -> w.isNotBlank() }) }
                else -> null
            }
        }
    }
}

/** Completion and catalog reads over the live socket; running a command belongs to the chat. */
class SlashApi(private val connection: GatewayConnection) {

    suspend fun catalog(runtimeSessionId: String?, profile: String?): SlashCatalog {
        val result = client().request(
            "commands.catalog",
            buildJsonObject {
                runtimeSessionId?.let { put("session_id", it) }
                profile?.let { put("profile", it) }
            },
        )
        return SlashCatalog.parse(result as? JsonObject)
    }

    /**
     * Ranked matches for [text] (`/rev`, or `/personality al` for an argument). Each suggestion's text
     * is the whole line to put in the composer.
     */
    suspend fun complete(text: String, runtimeSessionId: String?): List<SlashSuggestion> {
        val result = client().request(
            "complete.slash",
            buildJsonObject {
                put("text", text)
                runtimeSessionId?.let { put("session_id", it) }
            },
        ) as? JsonObject
        return parseCompletions(text, result)
    }

    private fun client() = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    companion object {
        /** An argument item carries only the argument; it replaces [text] from `replace_from` on. */
        internal fun parseCompletions(text: String, result: JsonObject?): List<SlashSuggestion> {
            val replaceFrom = (result?.get("replace_from") as? JsonPrimitive)?.intOrNull ?: 1
            val argument = replaceFrom > 1
            val prefix = if (argument) text.take(replaceFrom) else ""
            return result?.get("items").asObjectList().mapNotNull { item ->
                val raw = item.string("text") ?: return@mapNotNull null
                val full = if (argument) prefix + raw else raw.withSlash()
                SlashSuggestion(
                    text = full,
                    label = item.string("display")?.takeIf { it.isNotBlank() }?.let { if (argument) it else it.withSlash() } ?: full,
                    description = item.string("meta").orEmpty(),
                    kind = when {
                        argument -> SlashKind.Option
                        item.string("kind") == "skill" -> SlashKind.Skill
                        else -> SlashKind.Command
                    },
                )
            }.distinctBy { it.text }
        }
    }
}

private fun String.withSlash(): String = if (startsWith("/")) this else "/$this"

private fun kotlinx.serialization.json.JsonElement?.pairs(): List<Pair<String, String>> =
    (this as? JsonArray).orEmpty().mapNotNull { pair ->
        val parts = (pair as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return@mapNotNull null
        parts.getOrNull(0)?.let { it to parts.getOrElse(1) { "" } }
    }

package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The gateway calls behind Bot Mode, over the dashboard socket like Desktop's plugin makes them. */
class BotsApi(private val connection: GatewayConnection) : BotChatBackend {

    /** `profiles.list`: every profile as a bot, with its chat previews and look. Cheap: the gateway caches it per profile. */
    suspend fun roster(): BotRoster = parseBotRoster(client().request("profiles.list", JsonObject(emptyMap())))

    /** The bot's picture (`profiles.get_asset`), or null when it has none and draws its shape instead. */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun avatar(name: String): ByteArray? {
        val reply = client().request(
            "profiles.get_asset",
            buildJsonObject {
                put("name", name)
                put("asset", "avatar")
            },
        ) as? JsonObject ?: return null
        if ((reply["found"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val data = (reply["data"] as? JsonPrimitive)?.contentOrNull ?: return null
        return runCatching { Base64.decode(data.substringAfter("base64,")) }.getOrNull()
    }

    /**
     * Changes some of [name]'s Bot Mode fields (null removes one), the way Desktop writes them: the gateway
     * merges `ui_meta` per top-level key, so the whole `hermes-bots` block goes, read fresh and named by its
     * revision so a concurrent edit from Desktop isn't overwritten; a lost race reads again and retries.
     */
    suspend fun updateMeta(name: String, changes: Map<String, JsonElement?>) {
        repeat(META_ATTEMPTS) {
            val bot = roster().bots.firstOrNull { it.name == name } ?: throw RpcException(4064, "No bot named $name.")
            val merged = JsonObject(
                (bot.metaBlock + changes.mapValues { it.value ?: JsonNull }).filterValues { it !is JsonNull },
            )
            val reply = client().request(
                "profiles.configure",
                buildJsonObject {
                    put("name", name)
                    put("ui_meta", buildJsonObject { put(BotMeta.KEY, merged) })
                    bot.metaRevision?.let { revision -> put("ui_meta_expected_revisions", buildJsonObject { put(BotMeta.KEY, revision) }) }
                },
            ) as? JsonObject
            val applied = reply?.get("applied") as? JsonObject
            if (applied?.get("ui_meta_conflicts") == null) {
                if ((applied?.get("ui_meta") as? JsonPrimitive)?.booleanOrNull == false) throw RpcException(0, "The gateway didn't save it.")
                return
            }
        }
        throw RpcException(0, "Changed elsewhere at the same time. Try again.")
    }

    /**
     * Makes the gateway watch [profile]'s chats, so `sessions.changed` fires when the bot's chat moves:
     * it only watches profiles a call has named, and the roster call doesn't count (change_watcher.py).
     */
    suspend fun watch(profile: String) {
        client().request(
            "session.list",
            buildJsonObject {
                put("profile", profile)
                put("title", BOT_CHAT_TITLE)
                put("limit", 1)
            },
        )
    }

    override suspend fun botChat(profile: String): BotSession? =
        roster().bots.firstOrNull { it.name == profile }?.canonicalSession?.takeIf { it.openId != null }

    /**
     * Starts the profile's Bot Chat the way Desktop does: a hidden session that follows the profile's current
     * model, titled at once so the name is claimed before anything else can take it (and the row exists
     * before the first prompt). Returns its stored id.
     */
    override suspend fun startBotChat(profile: String): String = startChat(profile).second

    /**
     * Makes a new bot, Desktop's New Agent: the profile (cloning the default's configuration, sharing its
     * keys), its look and title in `ui_meta` (which makes it a Bot Mode bot), then a check that it has a
     * model it can use. Returns whether it does; without one it is made but can't answer yet.
     */
    suspend fun createBot(draft: BotDraft): BotCreated {
        val client = client()
        client.request(
            "profiles.create",
            buildJsonObject {
                put("name", draft.profile)
                listOf(draft.title, draft.description).filter { it.isNotBlank() }.joinToString(" — ").takeIf { it.isNotEmpty() }
                    ?.let { put("description", it) }
                put("clone_from", Bot.DEFAULT)
                put("share_auth", true)
                put("soul", draft.soul.ifBlank { composeSoul(displayNameFor(draft.profile, draft.title), draft.profile, draft.title, draft.description) })
            },
            timeoutMs = SLOW_MS,
        )
        updateMeta(draft.profile, draft.look() + mapOf("created" to JsonPrimitive(currentTimeMillis())))
        val check = runCatching { runtimeCheck(draft.profile) }.getOrNull()
        return BotCreated(draft.profile, readyToChat = check?.ok != false, problem = check?.error)
    }

    /**
     * Whether [profile]'s model can be served now (`setup.runtime_check`, the resolver a new session uses).
     * Never an RPC error on the gateway's side: an unknown profile or a refused key is `ok: false`.
     */
    suspend fun runtimeCheck(profile: String): RuntimeCheck {
        val reply = client().request("setup.runtime_check", buildJsonObject { put("profile", profile) }) as? JsonObject
        return RuntimeCheck(
            ok = (reply?.get("ok") as? JsonPrimitive)?.booleanOrNull != false,
            error = (reply?.get("error") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Starts a just-made bot's Bot Chat with Desktop's intro, so the bot greets its owner once. Only for a
     * bot just made: any other open must never send it (each one is a model turn in the user's name).
     */
    suspend fun startWithIntro(profile: String): String {
        val (runtime, stored) = startChat(profile)
        client().request(
            "prompt.submit",
            buildJsonObject {
                put("session_id", runtime)
                put("text", KICKOFF)
            },
        )
        return stored
    }

    /**
     * Sends [text] to [profile]'s Bot Chat [storedSessionId] without opening it in the app, as a reply
     * typed into the bot's notification: the chat is attached on this socket and the prompt submitted;
     * the bot's answer shows up like any other.
     */
    suspend fun sendToBot(profile: String, storedSessionId: String, text: String) {
        val client = client()
        val resumed = client.request(
            "session.resume",
            buildJsonObject {
                put("session_id", storedSessionId)
                put("profile", profile)
                put("source", CLIENT_SOURCE)
                put("cols", TERMINAL_COLUMNS)
                put("omit_messages", true)
            },
        ) as? JsonObject
        val runtime = (resumed?.get("session_id") as? JsonPrimitive)?.contentOrNull ?: throw RpcException(0, "Couldn't open the bot's chat.")
        client.request(
            "prompt.submit",
            buildJsonObject {
                put("session_id", runtime)
                put("text", text)
            },
        )
    }

    /** What the editor shows of a bot beyond the roster: its SOUL and description (`profiles.describe`). */
    suspend fun describe(name: String): BotDetails {
        val reply = client().request("profiles.describe", buildJsonObject { put("name", name) }) as? JsonObject
        fun text(key: String) = (reply?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        return BotDetails(soul = text("soul"), description = text("description"))
    }

    /** Saves an edit: the profile's description and SOUL when they changed, and only the look fields that did. */
    suspend fun editBot(name: String, description: String?, soul: String?, look: Map<String, JsonElement?>) {
        if (description != null || soul != null) {
            client().request(
                "profiles.configure",
                buildJsonObject {
                    put("name", name)
                    description?.let { put("description", it) }
                    soul?.let { put("soul", it) }
                },
                timeoutMs = SLOW_MS,
            )
        }
        if (look.isNotEmpty()) updateMeta(name, look)
    }

    /**
     * Sets the model [profile] runs ([provider]'s [model]): the bot's own, which every chat following its
     * configuration picks up, its Bot Chat included. The gateway may want a pick confirmed first (a costly
     * or data-sharing model): then nothing is written and its warning comes back, to send again with
     * [confirmed] once the user agrees. Null when it was saved.
     */
    suspend fun setModel(profile: String, model: String, provider: String, confirmed: Boolean = false): String? {
        val reply = client().request(
            "profiles.configure",
            buildJsonObject {
                put("name", profile)
                put("model", model)
                put("provider", provider)
                if (confirmed) put("confirm_expensive_model", true)
            },
            timeoutMs = SLOW_MS,
        ) as? JsonObject
        if ((reply?.get("confirm_required") as? JsonPrimitive)?.booleanOrNull == true) {
            return (reply["confirm_message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Use this model?"
        }
        val saved = ((reply?.get("applied") as? JsonObject)?.get("model") as? JsonPrimitive)?.booleanOrNull
        if (saved == false) throw RpcException(0, "The gateway didn't save the model.")
        return null
    }

    /**
     * Copies [bot] as a new bot named `<name>-2` (the first free number), configuration, skills, SOUL and
     * memory included, with the same look and "(copy)" after its title. Returns the new profile.
     */
    suspend fun duplicate(bot: Bot, taken: Set<String>): String {
        val name = (2..99).map { n -> bot.name.take(64 - "-$n".length) + "-$n" }.firstOrNull { it !in taken }
            ?: throw RpcException(0, "No free name for the copy.")
        client().request(
            "profiles.create",
            buildJsonObject {
                put("name", name)
                put("clone_from", bot.name)
                bot.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            },
            timeoutMs = SLOW_MS,
        )
        val look = bot.metaBlock.filterKeys { it != "created" && it != "chat" } +
            ("title" to JsonPrimitive("${bot.label} (copy)")) + ("created" to JsonPrimitive(currentTimeMillis()))
        updateMeta(name, look)
        return name
    }

    private suspend fun startChat(profile: String): Pair<String, String> {
        val client = client()
        val created = client.request(
            "session.create",
            buildJsonObject {
                put("profile", profile)
                put("title", BOT_CHAT_TITLE)
                put("hidden", true)
                put("follow_profile_config", true)
                put("source", CLIENT_SOURCE)
                put("cols", TERMINAL_COLUMNS)
            },
        ) as? JsonObject ?: throw RpcException(0, "Empty session.create reply")
        val runtime = (created["session_id"] as? JsonPrimitive)?.contentOrNull
        val stored = (created["stored_session_id"] as? JsonPrimitive)?.contentOrNull
        if (runtime == null || stored == null) throw RpcException(0, "session.create returned no session")
        try {
            client.request(
                "session.title",
                buildJsonObject {
                    put("session_id", runtime)
                    put("title", BOT_CHAT_TITLE)
                },
            )
        } catch (e: RpcException) {
            // Our runtime is of no use now. The gateway doesn't prune the empty row it made for a
            // non-"tui" source, so let go of the runtime and remove the row rather than leave a stray.
            discard(client, profile, runtime, stored)
            if (TITLE_TAKEN.containsMatchIn(e.message)) throw BotChatTakenException()
            throw e
        }
        return runtime to stored
    }

    private suspend fun discard(client: JsonRpcClient, profile: String, runtime: String, stored: String) {
        runCatching { client.request("session.close", buildJsonObject { put("session_id", runtime) }) }
        runCatching {
            client.request(
                "session.delete",
                buildJsonObject {
                    put("session_id", stored)
                    put("profile", profile)
                },
            )
        }
    }

    private fun client(): JsonRpcClient = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    companion object {
        /** A Bot Chat is the profile's session with exactly this title; there is at most one. */
        const val BOT_CHAT_TITLE = "Bot Chat"

        /** Same surface hint as [dev.hermeskotlin.core.chat.ChatSession]: Markdown and pictures show here. */
        private const val CLIENT_SOURCE = "desktop"
        private const val TERMINAL_COLUMNS = 80
        private val TITLE_TAKEN = Regex("already in use", RegexOption.IGNORE_CASE)
        private const val META_ATTEMPTS = 4

        /** Making or copying a profile seeds its skills and writes files; it takes a while. */
        private const val SLOW_MS = 120_000L

        /** Desktop's first line of a new bot's chat (canonical-chat.ts `kickoffText`). */
        const val KICKOFF = "Hey, tell me about yourself!"

        private fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
    }
}

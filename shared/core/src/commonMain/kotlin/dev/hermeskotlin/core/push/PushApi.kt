package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** What the herald-push plugin answers to `/herald-push …` (JSON in the command's output). */
@Serializable
data class PushPluginReply(
    val ok: Boolean = false,
    val error: String? = null,
    val version: String? = null,
    val gateway: PushGateway? = null,
    val watching: Boolean = false,
) {
    /** True when the plugin is at least [minimum] ("0.1.1"); an unparseable version counts as too old. */
    fun versionAtLeast(minimum: String): Boolean {
        val have = version?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
        val want = minimum.split('.').map { it.toInt() }
        for (i in 0 until maxOf(have.size, want.size)) {
            val a = have.getOrElse(i) { 0 }
            val b = want.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }
}

/**
 * A newer herald-push plugin than the gateway or a bot runs: [installed] there, [available] as this Herald was reviewed
 * with. [needed] when this Herald can't work with the installed one. Shown to the user, installed only when asked.
 */
data class PluginUpdate(val installed: String, val available: String, val needed: Boolean)

/** Where the gateway's herald-push plugin stands after [PushApi.prepare]. */
sealed interface PluginState {
    /** Usable; [update] when a newer one is available. */
    data class Ready(val info: PushPluginReply, val update: PluginUpdate? = null, val detail: String? = null) : PluginState
    data object Missing : PluginState

    /** Too old for this Herald, and not updated because the user hasn't asked to. */
    data class Outdated(val update: PluginUpdate) : PluginState
    data class Failed(val reason: String?) : PluginState
}

/** A phone tool call the agent is waiting on (`/herald-push phone pending`). */
@Serializable
data class PhoneRequest(
    val id: String,
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap()),
    /** The chat the agent called it from, empty when it doesn't say. */
    val session: String = "",
    /** When the agent stops waiting, in the gateway's epoch seconds. */
    val expires: Long = 0,
    /**
     * The gateway's time when it listed the call (epoch seconds; 0 from a plugin before 0.3.3): [expires] is
     * measured against it, never against the phone's own clock, which may be off.
     */
    @Transient val gatewayNow: Long = 0,
)

/** What the plugin answers to `/herald-push phone …`. */
@Serializable
private data class PhoneReply(
    val ok: Boolean = false,
    val error: String? = null,
    val tools: List<String> = emptyList(),
    val requests: List<PhoneRequest> = emptyList(),
    val phones: List<KnownPhone> = emptyList(),
    /** The gateway's time with `pending`, epoch seconds (0.3.3 on). */
    val now: Long = 0,
)

/**
 * A phone the gateway knows for phone tools, as `phone list` shows it: its [name], the [tools] it has on, when it
 * was [seen] last and [used] for a message last (the gateway's epoch seconds), and whether it's this phone ([me]).
 */
@Serializable
data class KnownPhone(
    val id: String,
    val name: String = "Phone",
    val tools: List<String> = emptyList(),
    val seen: Long = 0,
    val used: Long = 0,
    val me: Boolean = false,
)

/**
 * The gateway side of push, over the dashboard socket: the herald-push plugin is installed with the same
 * call as Desktop's Plugins hub, and talked to through its `/herald-push` command, which only answers the
 * dashboard (never a messaging platform). Registering over this authenticated link is what makes the
 * gateway keys the phone pins trustworthy.
 */
class PushApi(private val connection: GatewayConnection) {

    private val preparing = Mutex()
    private var updateFailure: Pair<JsonRpcClient, List<String>>? = null
    private val _updates = MutableStateFlow(0)

    /**
     * Counts the plugin updates installed: push and phone tools share the plugin, so one updating it tells the
     * other to look again instead of still offering the update.
     */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    private val _registrations = MutableStateFlow(0)

    /**
     * Counts this phone's push registrations made or given up: phone tools tell the gateway the push id a call can
     * ring through, so one made after the tools were reported reaches the gateway too.
     */
    val registrations: StateFlow<Int> = _registrations.asStateFlow()

    fun registrationChanged() = _registrations.update { it + 1 }

    /** The plugin's state, or null when it isn't installed (or is off) on this gateway. */
    suspend fun info(): PushPluginReply? = command("info")

    suspend fun register(registration: PushRegistration): PushPluginReply =
        command("register " + HermesJson.encodeToString(PushRegistration.serializer(), registration))
            ?: throw RpcException(0, "The herald-push plugin isn't installed on the gateway.")

    suspend fun unregister(deviceId: String): Boolean = command("unregister $deviceId")?.ok == true

    /** Asks the gateway to send this phone a test notification through ntfy. */
    suspend fun test(deviceId: String): Boolean = command("test $deviceId")?.ok == true

    /**
     * Installs the plugin from its repository at the reviewed commit (git clone on the gateway, live without
     * a restart). With [force] an existing copy is replaced, which is how an old version is brought up to date.
     */
    suspend fun install(force: Boolean = false, profile: String? = null, enabled: Boolean = true) {
        client().request(
            "plugins.manage",
            buildJsonObject {
                put("action", "install")
                put("identifier", PLUGIN_REPO)
                // A reviewed commit, never whatever the branch holds today: this code runs on the gateway.
                put("ref", PLUGIN_REF)
                put("enable", enabled)
                put("force", force)
                if (profile != null) put("profile", profile)
            },
            timeoutMs = INSTALL_TIMEOUT_MS,
        )
    }

    /**
     * Switches an installed but disabled copy on. False when the gateway has no such plugin (then it needs
     * [install]) or refused.
     */
    suspend fun enable(): Boolean {
        val reply = try {
            client().request(
                "plugins.manage",
                buildJsonObject {
                    put("action", "toggle")
                    put("name", PLUGIN_COMMAND)
                    put("enable", true)
                },
                timeoutMs = INSTALL_TIMEOUT_MS,
            )
        } catch (e: RpcException) {
            return false
        }
        return ((reply as? JsonObject)?.get("ok") as? JsonPrimitive)?.booleanOrNull == true
    }

    /**
     * The plugin, ready: switched on when it was only disabled, installed when missing (only with [install]),
     * and reinstalled at the reviewed commit only with [update], which the user asks for: code on their gateway
     * never changes behind their back. Older than [minimum] without it, it's [PluginState.Outdated]. [onStep]
     * says what it's doing. One at a time: push and phone tools both prepare on connect, and two installs must
     * not race on the gateway.
     */
    suspend fun prepare(
        install: Boolean,
        minimum: String = MIN_PLUGIN_VERSION,
        update: Boolean = false,
        onStep: (String) -> Unit = {},
    ): PluginState = preparing.withLock {
        val rpc = client()
        if (update || updateFailure?.first !== rpc) updateFailure = null
        var info = info()
        if (info == null && install) {
            // Present but switched off (in Desktop's Plugins hub, say): a toggle is enough, and an
            // install would be refused as "already exists".
            onStep("Setting up on the gateway…")
            if (enable()) info = info()
            if (info == null) {
                onStep("Installing on the gateway…")
                install()
                info = info()
            }
        }
        val failures = mutableListOf<String>()
        var changed = false
        if (update && info != null && info.ok && !info.versionAtLeast(PLUGIN_VERSION)) {
            onStep("Updating the gateway's plugin to $PLUGIN_VERSION…")
            try {
                install(force = true)
                changed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "gateway: ${e.message}"
            }
            info = info()
            if (info == null || !info.ok || !info.versionAtLeast(PLUGIN_VERSION)) {
                failures += "The gateway's herald-push plugin was not updated to $PLUGIN_VERSION."
            }
        }
        // An update always looks afresh: the copies may have changed since (Desktop's Plugins hub, another phone).
        if (update) scannedBots = null
        val copies = if (info?.ok == true && (update || info.versionAtLeast(minimum))) botCopies(rpc, failures) else emptyList()
        val pending = copies.toMutableList()
        if (update) {
            for (copy in copies) {
                onStep("Updating ${copy.profile}'s plugin to $PLUGIN_VERSION…")
                try {
                    install(force = true, profile = copy.profile, enabled = copy.enabled)
                    changed = true
                    val installed = botCopy(copy.profile)
                    if (installed == null || !PushPluginReply(version = installed.version).versionAtLeast(PLUGIN_VERSION) || installed.enabled != copy.enabled) {
                        failures += "${copy.profile}: the plugin update could not be verified."
                    } else pending.remove(copy)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += "${copy.profile}: ${e.message}"
                }
            }
        }
        // Both bridges refresh after an update; keep partial failures visible until another ask or connection.
        if (update) updateFailure = failures.takeIf {
            it.isNotEmpty() && info?.ok == true && info.versionAtLeast(PLUGIN_VERSION)
        }?.let { rpc to it.toList() }
        // What's still old after the update, verified copy by copy: the refreshes it sets off needn't look again.
        // Only after a look that saw every profile; else they look again, and say again which they couldn't.
        if (update && copies.isNotEmpty() && scannedBots?.rpc === rpc) scannedBots = ScannedBots(rpc, pending.toList(), emptyList())
        if (changed) _updates.update { it + 1 }
        val detail = (failures + updateFailure?.second.orEmpty()).distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")
        if (detail != null && (info == null || !info.ok || !info.versionAtLeast(minimum))) {
            return@withLock PluginState.Failed(detail)
        }
        val botUpdate = (pending.firstOrNull()?.version ?: detail?.let { "unknown" })?.let {
            PluginUpdate(it, PLUGIN_VERSION, needed = false)
        }
        when {
            info == null -> PluginState.Missing
            !info.ok -> PluginState.Failed(info.error)
            !info.versionAtLeast(minimum) -> PluginState.Outdated(PluginUpdate(info.version ?: "unknown", PLUGIN_VERSION, needed = true))
            !info.versionAtLeast(PLUGIN_VERSION) -> PluginState.Ready(info, PluginUpdate(info.version ?: "unknown", PLUGIN_VERSION, needed = false), detail)
            else -> PluginState.Ready(info, botUpdate, detail)
        }
    }

    private data class BotCopy(val profile: String, val version: String, val enabled: Boolean)

    /** One look at the bots' copies on connection [rpc]: the old ones, and what couldn't be looked at. */
    private data class ScannedBots(val rpc: JsonRpcClient, val copies: List<BotCopy>, val failures: List<String>)

    /**
     * Kept for the connection: push and phone tools both prepare on every connect, and each look is a call per
     * profile, each a fraction of a second on the gateway, while every other prepare waits on the lock. Only a
     * look that saw every profile is kept: one that failed somewhere (a timeout, say) is tried again.
     */
    private var scannedBots: ScannedBots? = null

    private suspend fun botCopies(rpc: JsonRpcClient, failures: MutableList<String>): List<BotCopy> {
        val scan = scannedBots?.takeIf { it.rpc === rpc }
            ?: scanBots(rpc).also { scannedBots = it.takeIf { scan -> scan.failures.isEmpty() } }
        failures += scan.failures
        return scan.copies
    }

    /** Only existing downloaded copies need replacing; no plugin is installed on an unrelated profile. */
    private suspend fun scanBots(rpc: JsonRpcClient): ScannedBots {
        val failures = mutableListOf<String>()
        val bots = try {
            BotsApi(connection).roster().bots
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Older gateways have no profiles API.
            if (!(e is RpcException && e.code == -32601)) failures += "bot profiles: ${e.message}"
            return ScannedBots(rpc, emptyList(), failures)
        }
        val copies = bots.mapNotNull { bot ->
            try {
                // A version that doesn't read as one may be newer (a dev copy): never replaced.
                botCopy(bot.name)?.takeIf { it.version.isVersion() && !PushPluginReply(version = it.version).versionAtLeast(PLUGIN_VERSION) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "${bot.name}: ${e.message}"
                null
            }
        }
        return ScannedBots(rpc, copies, failures)
    }

    private fun String.isVersion(): Boolean = isNotEmpty() && split('.').all { it.toIntOrNull() != null }

    private suspend fun botCopy(profile: String): BotCopy? {
        val result = client().request("plugins.manage", buildJsonObject {
            put("action", "list")
            put("profile", profile)
        }) as? JsonObject
        val plugins = result?.get("plugins") as? JsonArray
            ?: throw RpcException(0, "The gateway didn't return the profile's plugins.")
        val copy = plugins.filterIsInstance<JsonObject>().firstOrNull {
            (it["name"] as? JsonPrimitive)?.contentOrNull == PLUGIN_COMMAND &&
                (it["source"] as? JsonPrimitive)?.contentOrNull in setOf("user", "git")
        } ?: return null
        return BotCopy(profile, (copy["version"] as? JsonPrimitive)?.contentOrNull ?: "unknown",
            (copy["status"] as? JsonPrimitive)?.contentOrNull == "enabled")
    }

    /**
     * Tells the gateway which phone tools this phone has on; returns the ones it will offer, or null without the
     * plugin. [key] is this phone's secret: the first report binds the id to it, and every phone call needs it.
     */
    suspend fun phoneTools(deviceId: String, key: String, name: String, tools: List<String>, pushId: String?): List<String>? {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("key", key)
            put("name", name)
            // The push registration a call nudges; only ever a nudge, the call itself comes over this socket.
            if (pushId != null) put("push_id", pushId)
            put("tools", JsonArray(tools.map(::JsonPrimitive)))
        }
        val reply = output("phone tools $body")?.let(::phoneReply) ?: return null
        if (!reply.ok) throw RpcException(0, reply.error ?: "The gateway refused the phone tools.")
        return reply.tools
    }

    /** This phone sends turns on chat connection [sid]: the agent's phone tool calls from them come here. */
    suspend fun phoneHere(deviceId: String, key: String, sid: String): Boolean {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("key", key)
            put("sid", sid)
        }
        return output("phone here $body")?.let(::phoneReply)?.ok == true
    }

    /** Every phone the gateway knows, in the order calls no phone started (cron jobs) try them; null when refused. */
    suspend fun phoneList(deviceId: String, key: String): List<KnownPhone>? = phones("list", deviceId, key) {}

    /** Puts the phones [ids] first, in that order; returns the list as it is then. */
    suspend fun phoneOrder(deviceId: String, key: String, ids: List<String>): List<KnownPhone>? =
        phones("order", deviceId, key) { put("ids", JsonArray(ids.map(::JsonPrimitive))) }

    /** Forgets another phone, [id]; returns the list as it is then. */
    suspend fun phoneRemove(deviceId: String, key: String, id: String): List<KnownPhone>? =
        phones("remove", deviceId, key) { put("id", id) }

    private suspend fun phones(op: String, deviceId: String, key: String, more: JsonObjectBuilder.() -> Unit): List<KnownPhone>? {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("key", key)
            more()
        }
        return output("phone $op $body")?.let(::phoneReply)?.takeIf { it.ok }?.phones
    }

    /** The tool calls waiting for this phone, or null without the plugin. */
    suspend fun phonePending(deviceId: String, key: String): List<PhoneRequest>? {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("key", key)
        }
        val reply = output("phone pending $body")?.let(::phoneReply)?.takeIf { it.ok } ?: return null
        return reply.requests.map { it.copy(gatewayNow = reply.now) }
    }

    /** Hands the agent the [result] of a tool call, or the [error] it failed with. */
    suspend fun phoneAnswer(deviceId: String, key: String, requestId: String, result: JsonElement?, error: String?): Boolean {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("key", key)
            put("id", requestId)
            if (error != null) put("error", error) else put("result", result ?: JsonNull)
        }
        return output("phone answer $body")?.let(::phoneReply)?.ok == true
    }

    /** What `/herald-push cron <arg>` printed (the Inbox's calls), or null when the plugin isn't loaded. */
    suspend fun cron(arg: String): String? = output("cron $arg")

    private fun phoneReply(output: String): PhoneReply? =
        runCatching { HermesJson.decodeFromString(PhoneReply.serializer(), output) }.getOrNull()

    private suspend fun command(arg: String): PushPluginReply? {
        val output = output(arg) ?: return null
        return runCatching { HermesJson.decodeFromString(PushPluginReply.serializer(), output) }.getOrNull()
    }

    /** What `/herald-push <arg>` printed, or null when the plugin isn't loaded. */
    private suspend fun output(arg: String): String? {
        val reply = try {
            client().request(
                "command.dispatch",
                buildJsonObject {
                    put("name", PLUGIN_COMMAND)
                    put("arg", arg)
                },
            )
        } catch (e: RpcException) {
            // 4018: no such command, i.e. the plugin isn't loaded.
            if (e.code == NOT_A_COMMAND) return null
            throw e
        }
        val obj = reply as? JsonObject ?: return null
        if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "plugin") return null
        return (obj["output"] as? JsonPrimitive)?.contentOrNull
    }

    private fun client(): JsonRpcClient = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    companion object {
        const val PLUGIN_COMMAND = "herald-push"
        const val PLUGIN_REPO = "https://github.com/Giton22/hermes-herald-push"

        /** The plugin commit this Herald was built and reviewed against. */
        const val PLUGIN_REF = "c88f02785286ca7d408e50ad530515be3885f8ba"

        /** The version at [PLUGIN_REF]: an older copy on the gateway is offered this as an update. */
        const val PLUGIN_VERSION = "0.5.1"

        /** The oldest plugin this Herald works with; an older copy waits for the user to update it. */
        const val MIN_PLUGIN_VERSION = "0.1.1"

        /** The oldest plugin with phone tools as this Herald speaks them (calls go to the phone the chat is on). */
        const val MIN_PHONE_PLUGIN_VERSION = "0.3.0"

        /** The oldest plugin that lists, orders and removes phones; older ones work without the list. */
        const val PHONE_LIST_PLUGIN_VERSION = "0.3.1"
        private const val NOT_A_COMMAND = 4018
        private const val INSTALL_TIMEOUT_MS = 180_000L
    }
}

/** This phone's push identity, as the platform keeps it (Android: keys wrapped by the AndroidKeyStore). */
interface PushKeys {
    val deviceId: String

    /** What to send the gateway; [name] is how the gateway lists this phone. */
    fun registration(name: String): PushRegistration

    /** The gateway keys trusted for [gatewayUrl], pinned when it answered a registration. */
    fun pinned(gatewayUrl: String): PushGateway?

    /** Whether [pin] would take [keys]: well-formed, on the curve. Checked before registering with them. */
    fun accepts(keys: PushGateway): Boolean

    /** Trusts [keys] for [gatewayUrl] and no other gateway: one phone identity serves one gateway. */
    fun pin(gatewayUrl: String, keys: PushGateway)

    /**
     * Drops this identity (keys and topics) because the gateway at [gatewayUrl] holds it and should stop
     * using it; the gateway is told later, when it can be reached ([retired]). A fresh identity follows.
     */
    fun retire(gatewayUrl: String)

    /** Identities given up but not yet unregistered from their gateway, by gateway URL. */
    fun retired(gatewayUrl: String): Set<String>
    fun forgetRetired(gatewayUrl: String, deviceId: String)

    /** The URL of the gateway this identity is registered with, if any. */
    fun pinnedGatewayUrl(): String?
}

/** Whether a gateway was told about this phone, as a short status line. */
data class PushStatus(val state: State = State.Unknown, val detail: String? = null, val update: PluginUpdate? = null) {
    enum class State { Unknown, Off, NotInstalled, Outdated, Working, On, Failed }
}

package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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
 * The gateway side of push, over the dashboard socket: the herald-push plugin is installed with the same
 * call as Desktop's Plugins hub, and talked to through its `/herald-push` command, which only answers the
 * dashboard (never a messaging platform). Registering over this authenticated link is what makes the
 * gateway keys the phone pins trustworthy.
 */
class PushApi(private val connection: GatewayConnection) {

    /** The plugin's state, or null when it isn't installed (or is off) on this gateway. */
    suspend fun info(): PushPluginReply? = command("info")

    suspend fun register(registration: PushRegistration): PushPluginReply =
        command("register " + HermesJson.encodeToString(PushRegistration.serializer(), registration))
            ?: throw RpcException(0, "Herald push isn't installed on the gateway.")

    suspend fun unregister(deviceId: String): Boolean = command("unregister $deviceId")?.ok == true

    /** Asks the gateway to send this phone a test notification through ntfy. */
    suspend fun test(deviceId: String): Boolean = command("test $deviceId")?.ok == true

    /**
     * Installs the plugin from its repository at the reviewed commit (git clone on the gateway, live without
     * a restart). With [force] an existing copy is replaced, which is how an old version is brought up to date.
     */
    suspend fun install(force: Boolean = false) {
        client().request(
            "plugins.manage",
            buildJsonObject {
                put("action", "install")
                put("identifier", PLUGIN_REPO)
                // A reviewed commit, never whatever the branch holds today: this code runs on the gateway.
                put("ref", PLUGIN_REF)
                put("enable", true)
                put("force", force)
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

    private suspend fun command(arg: String): PushPluginReply? {
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
        val output = (obj["output"] as? JsonPrimitive)?.contentOrNull ?: return null
        return runCatching { HermesJson.decodeFromString(PushPluginReply.serializer(), output) }.getOrNull()
    }

    private fun client(): JsonRpcClient = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    companion object {
        const val PLUGIN_COMMAND = "herald-push"
        const val PLUGIN_REPO = "https://github.com/Giton22/hermes-herald-push"

        /** The plugin commit this Herald was built and reviewed against. */
        const val PLUGIN_REF = "4c0a053cf527d2771a62d553d8624b52175154dd"

        /** The oldest plugin this Herald works with; an older copy is reinstalled at [PLUGIN_REF]. */
        const val MIN_PLUGIN_VERSION = "0.1.1"
        private const val NOT_A_COMMAND = 4018
        private const val INSTALL_TIMEOUT_MS = 180_000L
    }
}

/** This phone's push identity, as the platform keeps it (Android: keys wrapped by the AndroidKeyStore). */
interface PushKeys {
    val deviceId: String

    /** Null when this phone can receive pushes; otherwise why it can't, shown instead of setting up. */
    val unavailable: String? get() = null

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
data class PushStatus(val state: State = State.Unknown, val detail: String? = null) {
    enum class State { Unknown, Off, NotInstalled, Working, On, Failed }
}

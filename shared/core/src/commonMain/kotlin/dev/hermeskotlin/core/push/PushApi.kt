package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
)

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

    /** Installs and enables the plugin from its repository (git clone on the gateway, live without a restart). */
    suspend fun install() {
        client().request(
            "plugins.manage",
            buildJsonObject {
                put("action", "install")
                put("identifier", PLUGIN_REPO)
                put("enable", true)
            },
            timeoutMs = INSTALL_TIMEOUT_MS,
        )
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
    fun pin(gatewayUrl: String, keys: PushGateway)
    fun unpin(gatewayUrl: String)
}

/** Whether a gateway was told about this phone, as a short status line. */
data class PushStatus(val state: State = State.Unknown, val detail: String? = null) {
    enum class State { Unknown, Off, NotInstalled, Working, On, Failed }
}

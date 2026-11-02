package dev.hermeskotlin.core.plugins

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.JsonRpcClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** A user-supplied source, never a shell command. A fragment selects a plugin subdirectory, not a ref. */
data class GitPluginSpec(val id: String, val repository: String, val revision: String) {
    init {
        require(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*").matches(id))
        require(Regex("https://[a-zA-Z0-9.-]+(?::[0-9]{1,5})?/[a-zA-Z0-9._~-]+(?:/[a-zA-Z0-9._~-]+)*(?:#[a-zA-Z0-9._~-]+(?:/[a-zA-Z0-9._~-]+)*)?").matches(repository))
        val base = repository.substringBefore('#')
        require(!base.contains("..") && base.split('/').none { it == "." })
        require(repository.substringAfter('#', "").split('/').none { it == "." || it == ".." })
        require(Regex("[a-fA-F0-9]{40}").matches(revision))
    }
}

sealed interface GitInstallResult {
    data class Reported(val status: String) : GitInstallResult
    data class Existing(val detail: String) : GitInstallResult
    data class Uncertain(val detail: String) : GitInstallResult
    data class Refused(val detail: String) : GitInstallResult
}

/** Launch profile only. A gateway-reported pin is not an independent verification. */
class GitPluginInstaller(private val connection: GatewayConnection, private val rpc: JsonRpcClient, private val targetValid: () -> Boolean) {
    private val lock = Mutex()
    private var eligible: GitPluginSpec? = null
    private fun current() = targetValid() && (connection.state.value as? ConnectionState.Connected)?.client === rpc
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private suspend fun rows(): List<JsonObject> {
        val result = rpc.request("plugins.manage", buildJsonObject { put("action", "list") }) as? JsonObject ?: error("Invalid list")
        return (result["plugins"] as? JsonArray ?: error("Invalid list")).map {
            (it as? JsonObject)?.also { row -> check(!row.text("name").isNullOrBlank()) } ?: error("Invalid row")
        }
    }
    private fun matching(rows: List<JsonObject>, id: String) = rows.filter {
        it.text("name")?.equals(id, ignoreCase = true) == true || it.text("key")?.equals(id, ignoreCase = true) == true
    }
    private fun verified(rows: List<JsonObject>, spec: GitPluginSpec): JsonObject? {
        val row = matching(rows, spec.id).singleOrNull() ?: return null
        if (row.text("name") != spec.id || row.text("pinned_sha")?.equals(spec.revision, true) != true) return null
        if (row.text("status") !in listOf("enabled", "disabled", "not enabled")) return null
        return row
    }
    suspend fun install(spec: GitPluginSpec): GitInstallResult = lock.withLock {
        eligible = null
        if (!current()) return@withLock GitInstallResult.Refused("Gateway connection changed; reopen the sheet.")
        val existing = try { matching(rows(), spec.id) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            return@withLock GitInstallResult.Refused("Cannot inspect existing plugins; no install sent.")
        }
        if (existing.isNotEmpty()) return@withLock GitInstallResult.Existing("Existing or ambiguous plugin was not replaced.")
        if (!current()) return@withLock GitInstallResult.Refused("Gateway connection changed; no install sent.")
        try {
            val reply = rpc.request("plugins.manage", buildJsonObject {
                put("action", "install"); put("identifier", spec.repository); put("ref", spec.revision)
                put("force", false); put("enable", false)
            }, timeoutMs = 120_000) as? JsonObject
            if (!current() || reply?.get("ok") != JsonPrimitive(true) || reply.text("plugin_name") != spec.id)
                return@withLock GitInstallResult.Uncertain("Install outcome or plugin identity uncertain; inspect the gateway before retrying.")
            val row = verified(rows(), spec)
            if (!current() || row == null || row.text("status") !in listOf("disabled", "not enabled")) GitInstallResult.Uncertain("Pin or disabled status missing/mismatched; inspect the gateway before retrying. Do not enable.")
            else { eligible = spec; GitInstallResult.Reported(row.text("status")!!) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            GitInstallResult.Uncertain("Install outcome unknown; inspect the gateway before retrying. Do not enable.")
        }
    }
    /** One user action: retain existing plugins, or install and enable the reported pin. */
    suspend fun setup(spec: GitPluginSpec): GitInstallResult {
        if (!current()) return GitInstallResult.Refused("Gateway connection changed; reopen setup.")
        val existing = try { matching(rows(), spec.id) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            return GitInstallResult.Refused("Cannot inspect plugins; no change sent.")
        }
        if (!current()) return GitInstallResult.Refused("Gateway connection changed; no change sent.")
        if (existing.isNotEmpty()) {
            val row = existing.singleOrNull()
            if (row == null || row.text("name") != spec.id) return GitInstallResult.Existing("Existing plugin identity is ambiguous; inspect the gateway.")
            when (row.text("status")) {
                "enabled" -> return GitInstallResult.Reported("enabled")
                "disabled", "not enabled" -> {
                    if (row.text("pinned_sha")?.equals(spec.revision, ignoreCase = true) != true)
                        return GitInstallResult.Existing("Existing plugin revision is unverified; inspect the gateway before enabling it.")
                }
                else -> return GitInstallResult.Existing("Existing plugin status is unclear; inspect the gateway.")
            }
            return try {
                val key = row.text("key")?.takeIf { it.isNotBlank() } ?: spec.id
                if (!current()) return GitInstallResult.Refused("Gateway connection changed; no change sent.")
                val reply = rpc.request("plugins.manage", buildJsonObject { put("action", "toggle"); put("key", key); put("enable", true) }) as? JsonObject
                if (!current() || reply?.get("ok") != JsonPrimitive(true)) return GitInstallResult.Uncertain("Enable outcome unknown; inspect the gateway.")
                val after = matching(rows(), spec.id).singleOrNull()
                if (!current() || after?.text("name") != spec.id || after.text("status") != "enabled") GitInstallResult.Uncertain("Enable readback uncertain; inspect the gateway.")
                else GitInstallResult.Reported("enabled")
            } catch (e: CancellationException) { throw e } catch (_: Exception) { GitInstallResult.Uncertain("Enable outcome unknown; inspect the gateway.") }
        }
        return when (val result = install(spec)) {
            is GitInstallResult.Reported -> when (val enabled = enable(spec)) {
                is GitInstallResult.Refused -> GitInstallResult.Uncertain("Installed but could not confirm enable safety; inspect the gateway before retrying.")
                else -> enabled
            }
            else -> result
        }
    }

    /** A separate explicit user action, rechecking the reported pin before writing. */
    suspend fun enable(spec: GitPluginSpec): GitInstallResult = lock.withLock {
        if (eligible != spec) return@withLock GitInstallResult.Refused("No matching confirmed install in this session; no enable sent.")
        eligible = null
        if (!current()) return@withLock GitInstallResult.Refused("Gateway connection changed; no enable sent.")
        try {
            val row = verified(rows(), spec)
            if (!current() || row == null || row.text("status") !in listOf("disabled", "not enabled"))
                return@withLock GitInstallResult.Refused("Pin or disabled status cannot be confirmed; no enable sent.")
            val key = row.text("key")?.takeIf { it.isNotBlank() } ?: spec.id
            val reply = rpc.request("plugins.manage", buildJsonObject { put("action", "toggle"); put("key", key); put("enable", true) }) as? JsonObject
            if (!current() || reply?.get("ok") != JsonPrimitive(true)) return@withLock GitInstallResult.Uncertain("Enable outcome unknown; inspect gateway before retrying.")
            val after = verified(rows(), spec)
            if (!current() || after?.text("status") != "enabled") GitInstallResult.Uncertain("Enable or pin readback uncertain; inspect gateway.")
            else GitInstallResult.Reported("enabled")
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            GitInstallResult.Uncertain("Enable outcome unknown; inspect gateway before retrying.")
        }
    }
}

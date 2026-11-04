package dev.hermeskotlin.core.plugins

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** An acknowledgment and list readback do not prove provenance, profile isolation, or route readiness. */
sealed interface VoiceInstallResult {
    data class Installed(val detail: String) : VoiceInstallResult
    data class Existing(val detail: String) : VoiceInstallResult
    data class Failed(val detail: String) : VoiceInstallResult
    /** A request may have reached the gateway. Inspect the profile before trying again. */
    data class Unknown(val detail: String) : VoiceInstallResult
    data class Skipped(val detail: String) : VoiceInstallResult
}

/**
 * Explicit user action only. Integration: call [profiles] when opening the picker, display its
 * [ProfileSnapshot.choices], then pass that same snapshot and the user's selected subset to [install].
 * Discard the snapshot on disconnect/reconnect or picker dismissal. Results are per profile.
 * The probes provide compatibility evidence only for trusted upstream-compatible gateways, not
 * provenance or attestation; downstream forks and backports can behave differently.
 */
class VoicePluginInstaller(private val connection: GatewayConnection, private val releaseRevision: String, private val targetValid: () -> Boolean = { true }) {
    init {
        require(Regex("[0-9a-fA-F]{40}").matches(releaseRevision)) { "A full 40-hex release revision is required" }
    }

    class ProfileSnapshot internal constructor(
        internal val owner: VoicePluginInstaller,
        internal val client: JsonRpcClient,
        choices: List<String>,
    ) {
        private val names = choices.toSet()
        val choices: List<String> get() = names.toList()
        internal fun contains(name: String) = name in names
    }

    private val lock = Mutex()
    private fun current(): JsonRpcClient? = if (targetValid()) (connection.state.value as? ConnectionState.Connected)?.client else null

    /** Throws a sanitized exception if the roster cannot be trusted. */
    suspend fun profiles(): ProfileSnapshot {
        val rpc = current() ?: throw IllegalStateException("Not connected to the gateway")
        try {
            val result = rpc.request("profiles.list", buildJsonObject { put("include_sessions", false) }) as? JsonObject
            val rows = result?.get("profiles") as? JsonArray ?: error("Invalid roster")
            val names = rows.map { row ->
                ((row as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() } ?: error("Invalid profile")
            }
            check(names.size == names.distinct().size && current() === rpc)
            // This list action is read-only. The known scoped handler resolves the profile before
            // dispatching any action; an older unscoped handler instead returns a normal list.
            var probe = "kyllene_scope_probe"
            while (probe in names) probe += "_x"
            val scoped = try {
                rpc.request("plugins.manage", buildJsonObject { put("action", "list"); put("profile", probe) })
                false
            } catch (e: RpcException) {
                e.code == 4064 && e.message == "profile '$probe' not found"
            }
            check(scoped && current() === rpc)
            var evidence = false
            for (name in names) {
                val rows = list(rpc, name)
                check(current() === rpc)
                val push = rows.filter { it.string("name") == "herald-push" }
                check(push.size <= 1)
                if (push.isNotEmpty()) {
                    check(push.single().string("pinned_sha")?.let { SHA.matches(it) } == true)
                    evidence = true
                }
            }
            check(evidence && current() === rpc)
            return ProfileSnapshot(this, rpc, names)
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            throw IllegalStateException("Could not establish compatible profile-scoped plugin management on the current gateway")
        }
    }


    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private suspend fun list(rpc: JsonRpcClient, profile: String): List<JsonObject> {
        val result = rpc.request("plugins.manage", buildJsonObject { put("action", "list"); put("profile", profile) }) as? JsonObject
        return ((result?.get("plugins") as? JsonArray) ?: error("Invalid plugin list")).map {
            (it as? JsonObject)?.also { row -> check(!row.string("name").isNullOrBlank()) } ?: error("Invalid plugin row")
        }
    }

    /** Explicitly installs only selected profiles; an uncertain write is never reported as success. */
    suspend fun install(snapshot: ProfileSnapshot, selectedProfiles: Collection<String>): Map<String, VoiceInstallResult> = lock.withLock {
        val selected = selectedProfiles.distinct()
        require(selected.isNotEmpty() && selected.all { snapshot.contains(it) }) { "Select only profiles from the picker" }
        require(snapshot.owner === this) { "Snapshot belongs to another installer" }
        val rpc = snapshot.client
        val results = linkedMapOf<String, VoiceInstallResult>()
        for (profile in selected) {
            if (current() !== rpc) {
                results[profile] = VoiceInstallResult.Skipped("Gateway changed; reopen the picker.")
                continue
            }
            val existing = try { list(rpc, profile).filter { it.string("name") == PLUGIN_NAME } }
            catch (e: CancellationException) { throw e } catch (_: Exception) {
                results[profile] = VoiceInstallResult.Skipped("Could not inspect existing plugins."); continue
            }
            if (existing.isNotEmpty()) {
                results[profile] = if (existing.size == 1) VoiceInstallResult.Existing("Existing plugin was not replaced.")
                    else VoiceInstallResult.Skipped("Ambiguous existing plugin rows; not replaced.")
                continue
            }
            if (current() !== rpc) {
                results[profile] = VoiceInstallResult.Skipped("Gateway changed; reopen the picker."); continue
            }
            results[profile] = try {
                val reply = rpc.request("plugins.manage", buildJsonObject {
                    put("action", "install"); put("profile", profile); put("identifier", SOURCE)
                    put("ref", releaseRevision); put("force", false); put("enable", true)
                }, timeoutMs = 120_000) as? JsonObject
                if (reply?.get("ok") != JsonPrimitive(true) || current() !== rpc) {
                    VoiceInstallResult.Unknown("Install outcome unknown; inspect the profile before retrying.")
                } else {
                    val rows = list(rpc, profile).filter { it.string("name") == PLUGIN_NAME }
                    if (current() === rpc && rows.size == 1 && rows.single().string("pinned_sha")?.equals(releaseRevision, ignoreCase = true) == true)
                        VoiceInstallResult.Installed("Gateway reports the requested pin; runtime and provenance are not verified.")
                    else VoiceInstallResult.Unknown("Pin readback missing or mismatched; inspect the profile before retrying.")
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                VoiceInstallResult.Unknown("Install outcome unknown; inspect the profile before retrying.")
            }
        }
        results
    }

    companion object {
        const val INSTALL_UNAVAILABLE = "Installation unavailable: exact revision enforcement and release review cannot be verified before installation."
        const val SOURCE = "https://github.com/Giton22/kyllene.git#plugins/kyllene_voice"
        const val PLUGIN_NAME = "kyllene_voice"
        private val SHA = Regex("[0-9a-fA-F]{40}")
    }
}

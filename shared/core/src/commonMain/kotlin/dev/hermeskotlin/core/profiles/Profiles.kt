package dev.hermeskotlin.core.profiles

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One Hermes profile: a separate agent on the gateway host with its own config, memory, skills and
 * sessions (`hermes -p <name>`). `GET /api/profiles` row (hermes_cli/web_routers/profiles.py).
 */
@Serializable
data class Profile(
    val name: String,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("display_name") val displayName: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val description: String? = null,
) {
    /** Desktop's label: the display name from profile.yaml when set, else the id. */
    val label: String get() = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: name
}

/** The gateway's profiles, and which one it runs as when a call names none. */
data class ProfileRoster(
    val profiles: List<Profile>,
    val launch: String,
    /** Whether the gateway said which profile it was launched as; when it didn't, [launch] is a guess at `default`. */
    val launchKnown: Boolean = true,
)

@Serializable
private data class ProfilesResponse(val profiles: List<Profile> = emptyList())

@Serializable
private data class ActiveProfileResponse(val current: String? = null)

class ProfilesApi(private val client: HttpClient) {

    /** `GET /api/profiles` plus `GET /api/profiles/active`, default profile first like Desktop's picker. */
    suspend fun roster(url: GatewayUrl): ApiResult<ProfileRoster> {
        val launch = (apiCall { client.get(url.resolve("api/profiles/active")) }
            .map { it.body<ActiveProfileResponse>().current } as? ApiResult.Success)?.value?.takeIf { it.isNotBlank() }
        return apiCall { client.get(url.resolve("api/profiles")) }.map { response ->
            val profiles = response.body<ProfilesResponse>().profiles.sortedByDescending { it.isDefault }
            ProfileRoster(profiles = profiles, launch = launch ?: DEFAULT, launchKnown = launch != null)
        }
    }

    /**
     * `DELETE /api/profiles/{name}`: removes the profile, chats, memory and all, for good. The gateway
     * refuses the default profile. It can take a few seconds while the profile's backend stops.
     */
    suspend fun delete(url: GatewayUrl, name: String): ApiResult<Unit> = apiCall {
        client.delete(url.resolve("api/profiles/${name.encodeURLPathPart()}")) {
            // The answer comes only once the backend has stopped; OkHttp's 10 s read timeout would cut it off first.
            timeout {
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        }
    }.map { }

    companion object {
        const val DEFAULT = "default"
    }
}

/**
 * The profile picked on each gateway. Null means none was picked: calls then carry no `profile` and
 * the gateway uses the one it was launched as, exactly as before profiles were supported.
 */
class ProfileStore(private val store: KeyValueStore) {
    private val mutex = Mutex()
    private val _changes = MutableSharedFlow<GatewayUrl>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Completed selection changes; observers re-read the active context rather than replaying old picks. */
    val changes = _changes.asSharedFlow()

    suspend fun get(gateway: GatewayUrl): String? = store.get(key(gateway))?.takeIf { it.isNotBlank() }

    suspend fun set(gateway: GatewayUrl, profile: String?) = mutex.withLock {
        val next = profile?.takeIf { it.isNotBlank() }
        if (get(gateway) == next) return@withLock
        if (next == null) store.remove(key(gateway)) else store.put(key(gateway), next)
        _changes.tryEmit(gateway)
        Unit
    }

    private fun key(gateway: GatewayUrl) = "profile.v1.$gateway"
}

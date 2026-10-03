package dev.hermeskotlin.core.profiles

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
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
data class ProfileRoster(val profiles: List<Profile>, val launch: String)

@Serializable
private data class ProfilesResponse(val profiles: List<Profile> = emptyList())

@Serializable
private data class ActiveProfileResponse(val current: String? = null)

class ProfilesApi(private val client: HttpClient) {

    /** `GET /api/profiles` plus `GET /api/profiles/active`, default profile first like Desktop's picker. */
    suspend fun roster(url: GatewayUrl): ApiResult<ProfileRoster> {
        val launch = apiCall { client.get(url.resolve("api/profiles/active")) }
            .map { it.body<ActiveProfileResponse>().current }
        return apiCall { client.get(url.resolve("api/profiles")) }.map { response ->
            val profiles = response.body<ProfilesResponse>().profiles.sortedByDescending { it.isDefault }
            ProfileRoster(
                profiles = profiles,
                launch = (launch as? ApiResult.Success)?.value?.takeIf { it.isNotBlank() } ?: DEFAULT,
            )
        }
    }

    companion object {
        const val DEFAULT = "default"
    }
}

/**
 * The profile picked on each gateway. Null means none was picked: calls then carry no `profile` and
 * the gateway uses the one it was launched as, exactly as before profiles were supported.
 */
class ProfileStore(private val store: KeyValueStore) {

    suspend fun get(gateway: GatewayUrl): String? = store.get(key(gateway))?.takeIf { it.isNotBlank() }

    suspend fun set(gateway: GatewayUrl, profile: String?) {
        if (profile == null) store.remove(key(gateway)) else store.put(key(gateway), profile)
    }

    private fun key(gateway: GatewayUrl) = "profile.v1.$gateway"
}

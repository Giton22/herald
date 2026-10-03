package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.serialization.Serializable

/** A gateway the user has chosen, plus the auth provider to sign in with. */
@Serializable
data class SavedGateway(
    val url: String,
    val provider: String = "basic",
) {
    val gatewayUrl: GatewayUrl get() = GatewayUrl.parse(url)
}

/** Remembers the current gateway. Multiple saved gateways come later (README: "Manage several saved gateways"). */
class GatewayRepository(private val store: KeyValueStore) {

    suspend fun current(): SavedGateway? = store.get(KEY)?.let {
        runCatching { HermesJson.decodeFromString(SavedGateway.serializer(), it) }.getOrNull()
    }

    suspend fun save(gateway: SavedGateway) {
        store.put(KEY, HermesJson.encodeToString(SavedGateway.serializer(), gateway))
    }

    suspend fun clear() = store.remove(KEY)

    private companion object {
        const val KEY = "gateway.current.v1"
    }
}

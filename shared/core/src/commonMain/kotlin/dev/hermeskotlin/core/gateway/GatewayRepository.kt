package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** A gateway the user has chosen, plus the auth provider to sign in with and the [name] they gave it. */
@Serializable
data class SavedGateway(
    val url: String,
    val provider: String = "basic",
    val name: String? = null,
) {
    val gatewayUrl: GatewayUrl get() = GatewayUrl.parse(url)

    /** What lists show: the given name, else the address without its scheme. */
    val label: String get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: url.substringAfter("://")
}

/**
 * The gateways the user signed in to. [currentUrl] is the one in use and opens on launch; [primaryUrl] is
 * listed first and takes over when the current one is removed.
 */
@Serializable
data class GatewayList(
    val gateways: List<SavedGateway> = emptyList(),
    val currentUrl: String? = null,
    val primaryUrl: String? = null,
) {
    val current: SavedGateway? get() = find(currentUrl)

    val primary: SavedGateway? get() = find(primaryUrl) ?: gateways.firstOrNull()

    /** The primary first, then the rest in the order they were added. */
    val ordered: List<SavedGateway> get() = primary?.let { p -> listOf(p) + gateways.filter { it.url != p.url } }.orEmpty()

    fun find(url: String?): SavedGateway? = url?.let { u -> gateways.find { it.url == u } }
}

/** Remembers the saved gateways and which one is in use. */
class GatewayRepository(private val store: KeyValueStore) {

    private val _list = MutableStateFlow(GatewayList())

    /** Empty until the first read or change. */
    val list: StateFlow<GatewayList> = _list.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false

    suspend fun all(): GatewayList = mutex.withLock { load() }

    suspend fun current(): SavedGateway? = all().current

    /** Adds [gateway] (or updates the saved one with its address) and makes it current; the first one saved is primary. */
    suspend fun save(gateway: SavedGateway): SavedGateway = edit { list ->
        val existing = list.find(gateway.url)
        val kept = gateway.copy(name = gateway.name ?: existing?.name)
        list.copy(
            gateways = if (existing == null) list.gateways + kept else list.gateways.map { if (it.url == kept.url) kept else it },
            currentUrl = kept.url,
            primaryUrl = list.primaryUrl ?: kept.url,
        )
    }.find(gateway.url)!!

    suspend fun select(url: String) {
        edit { list -> if (list.find(url) == null) list else list.copy(currentUrl = url) }
    }

    suspend fun setPrimary(url: String) {
        edit { list -> if (list.find(url) == null) list else list.copy(primaryUrl = url) }
    }

    /** A blank [name] goes back to showing the address. */
    suspend fun rename(url: String, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        edit { list -> list.copy(gateways = list.gateways.map { if (it.url == url) it.copy(name = trimmed) else it }) }
    }

    /** Forgets [url]. Removing the current gateway leaves none current; removing the primary hands it to the next one. */
    suspend fun remove(url: String) {
        edit { list ->
            val rest = list.gateways.filter { it.url != url }
            GatewayList(
                gateways = rest,
                currentUrl = list.currentUrl?.takeIf { it != url },
                primaryUrl = list.primaryUrl?.takeIf { it != url } ?: rest.firstOrNull()?.url,
            )
        }
    }

    private suspend fun edit(transform: (GatewayList) -> GatewayList): GatewayList = mutex.withLock {
        val next = transform(load())
        store.put(KEY, HermesJson.encodeToString(GatewayList.serializer(), next))
        _list.value = next
        next
    }

    private suspend fun load(): GatewayList {
        if (loaded) return _list.value
        val list = store.get(KEY)?.let { runCatching { HermesJson.decodeFromString(GatewayList.serializer(), it) }.getOrNull() }
            ?: migrate()
        loaded = true
        _list.value = list
        return list
    }

    /** Builds were storing one gateway; it becomes the first saved one. */
    private suspend fun migrate(): GatewayList {
        val single = store.get(LEGACY_KEY)?.let { runCatching { HermesJson.decodeFromString(SavedGateway.serializer(), it) }.getOrNull() }
            ?: return GatewayList()
        val list = GatewayList(listOf(single), currentUrl = single.url, primaryUrl = single.url)
        store.put(KEY, HermesJson.encodeToString(GatewayList.serializer(), list))
        store.remove(LEGACY_KEY)
        return list
    }

    private companion object {
        const val KEY = "gateways.v1"
        const val LEGACY_KEY = "gateway.current.v1"
    }
}

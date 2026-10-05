package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.KeyValueStore

/** What the sidebar lists: the profile's chats, or the gateway's bots. */
enum class SidebarMode { Chats, Bots }

/** The sidebar mode last picked on each gateway, so the app comes back to it. */
class SidebarModeStore(private val store: KeyValueStore) {

    suspend fun get(gateway: GatewayUrl): SidebarMode =
        store.get(key(gateway))?.let { saved -> SidebarMode.entries.firstOrNull { it.name == saved } } ?: SidebarMode.Chats

    suspend fun set(gateway: GatewayUrl, mode: SidebarMode) = store.put(key(gateway), mode.name)

    private fun key(gateway: GatewayUrl) = "sidebar.mode.v1.$gateway"
}

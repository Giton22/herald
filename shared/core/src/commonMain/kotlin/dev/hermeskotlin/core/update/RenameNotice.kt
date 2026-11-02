package dev.hermeskotlin.core.update

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.storage.KeyValueStore

/**
 * Tells people who had the app as Herald, once, that it is now Kyllene. A fresh install has no saved gateway at
 * its first launch, so it is marked seen at once and never shows the notice; an update keeps its gateways and
 * shows it until dismissed.
 */
class RenameNotice(private val store: KeyValueStore, private val gateways: GatewayRepository) {

    /** Whether to show the notice now. Call it at launch, before any sign-in can add a gateway. */
    suspend fun pending(): Boolean = when (store.get(KEY)) {
        SEEN -> false
        SHOWING -> true
        else -> {
            val upgraded = gateways.all().gateways.isNotEmpty()
            store.put(KEY, if (upgraded) SHOWING else SEEN)
            upgraded
        }
    }

    suspend fun dismiss() = store.put(KEY, SEEN)

    private companion object {
        const val KEY = "notice.rename.kyllene.v1"
        const val SEEN = "seen"
        const val SHOWING = "showing"
    }
}

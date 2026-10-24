package dev.hermeskotlin.core.update

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenameNoticeTest {

    @Test
    fun anUpdateShowsTheNoticeUntilItIsDismissed() = runTest {
        val store = InMemoryKeyValueStore()
        GatewayRepository(store).save(SavedGateway("https://hermes.example"))

        assertTrue(RenameNotice(store, GatewayRepository(store)).pending())
        // A relaunch before closing it still shows it.
        assertTrue(RenameNotice(store, GatewayRepository(store)).pending())

        RenameNotice(store, GatewayRepository(store)).dismiss()
        assertFalse(RenameNotice(store, GatewayRepository(store)).pending())
    }

    @Test
    fun aFreshInstallNeverShowsIt() = runTest {
        val store = InMemoryKeyValueStore()
        val gateways = GatewayRepository(store)

        assertFalse(RenameNotice(store, gateways).pending())
        // Signing in afterwards doesn't bring it up.
        gateways.save(SavedGateway("https://hermes.example"))
        assertFalse(RenameNotice(store, gateways).pending())
    }
}

package dev.hermeskotlin.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** Who the offline banner says it can't reach; its sentence is the catalogue's. */
class OfflineBannerTest {

    @Test
    fun itNamesTheGatewayItCantReach() {
        assertEquals("homelab", offlineTitle("homelab"))
        assertEquals("Hermes", offlineTitle(null))
        assertEquals("Hermes", offlineTitle(" "))
    }
}

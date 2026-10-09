package dev.hermeskotlin.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineBannerTest {

    @Test
    fun itNamesTheGatewayItCantReach() {
        assertEquals("Can't reach homelab", offlineTitle("homelab"))
        assertEquals("Can't reach Hermes", offlineTitle(null))
        assertEquals("Can't reach Hermes", offlineTitle(" "))
    }
}

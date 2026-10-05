package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.connection.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkStatusTest {

    @Test
    fun anUnreachableGatewayWaitsForTheNetwork() = assertEquals(
        "Waiting for network…",
        linkStatus(ConnectionState.Reconnecting(3, 0, "Can't reach gateway: Unable to resolve host")),
    )

    @Test
    fun aDroppedSocketReconnects() =
        assertEquals("Reconnecting…", linkStatus(ConnectionState.Reconnecting(1, 0, "Connection lost")))

    @Test
    fun aRetryUnderWayStillSaysReconnecting() = assertEquals("Reconnecting…", linkStatus(ConnectionState.Connecting(2)))

    @Test
    fun theFirstConnectAndAnUpOrFailedLinkSayNothing() {
        assertNull(linkStatus(ConnectionState.Connecting(1)))
        assertNull(linkStatus(ConnectionState.Idle))
        assertNull(linkStatus(ConnectionState.SessionExpired))
        assertNull(linkStatus(ConnectionState.Failed("4403")))
    }
}

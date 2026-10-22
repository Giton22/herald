package dev.hermeskotlin.core.gateway

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The engine's KeyManager, against loopback sockets: no Android store involved, fakes stand in for KeyChain. */
class MtlsKeyManagerTest {

    private fun manager(
        pending: ClientCertAliases = ClientCertAliases(),
        saved: List<SavedGateway> = emptyList(),
    ) = MtlsKeyManager(
        resolveAlias = { host -> clientCertAliasFor(host, pending, saved) },
        privateKey = { throw UnsupportedOperationException("no KeyChain in host tests") },
        chain = { throw UnsupportedOperationException("no KeyChain in host tests") },
    )

    private fun connectedSocket(): Socket {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", server.localPort))
        server.close()
        return socket
    }

    @Test fun noAliasForTheHostMeansNoCertificate() {
        connectedSocket().use {
            assertNull(manager().chooseClientAlias(arrayOf("RSA"), null, it))
        }
    }

    @Test fun anUnconnectedSocketHasNoHostToMatch() {
        assertNull(manager().chooseClientAlias(arrayOf("RSA"), null, Socket()))
    }

    @Test fun thePendingPickIsPresentedToItsHost() {
        val pending = ClientCertAliases()
        pending.set("127.0.0.1", "loopback-cert")
        connectedSocket().use {
            assertEquals("loopback-cert", manager(pending).chooseClientAlias(arrayOf("RSA"), null, it))
        }
    }

    @Test fun aSavedGatewaysCertificateIsPresentedToItsHost() {
        val saved = listOf(SavedGateway("https://127.0.0.1:9119", clientCertAlias = "saved-cert"))
        connectedSocket().use {
            assertEquals("saved-cert", manager(saved = saved).chooseClientAlias(arrayOf("RSA"), null, it))
        }
    }

    @Test fun anotherHostsAliasIsNotPresented() {
        val saved = listOf(SavedGateway("https://other.example.net", clientCertAlias = "other-cert"))
        connectedSocket().use {
            assertNull(manager(saved = saved).chooseClientAlias(arrayOf("RSA"), null, it))
        }
    }
}

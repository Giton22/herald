package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.request.get
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxMtlsTest {
    private val fixtures = LinuxClientCertificatesTest()
    private val password get() = "test-only".toCharArray()

    private fun trust(alias: String): X509TrustManager {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setCertificateEntry(alias, fixtures.fixture().getCertificate(alias))
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(store); trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }

    @Test fun theSameIdentityAuthenticatesRestAndWebSocketUpgrade() = runBlocking {
        Endpoint(keepAlive = true).use { endpoint ->
            val store = InMemoryKeyValueStore()
            val identities = LinuxClientCertificates(store)
            val pending = ClientCertAliases()
            val gateways = GatewayRepository(store)
            val alias = identities.import("localhost", endpoint.port, fixtures.bundle(), password)
            gateways.save(SavedGateway(endpoint.url, clientCertAlias = alias))
            val client = createHttpClient(desktopMtlsEngine(pending, gateways, identities, trust("server")))
            try {
                assertEquals(200, client.get(endpoint.url).status.value)
                withTimeout(5000) {
                    val ws = client.webSocketSession(endpoint.url.replace("https:", "wss:") + "/api/ws")
                    assertEquals("fixture", (ws.incoming.receive() as Frame.Text).readText())
                }
                assertEquals(listOf("CN=Synthetic client", "CN=Synthetic client"), endpoint.peers.toList())
                gateways.setClientCert(endpoint.url, null)
                pending.changed("localhost")
                assertFails { client.get(endpoint.url) }
                val replacement = identities.import("localhost", endpoint.port, fixtures.bundle("other"), password)
                gateways.setClientCert(endpoint.url, replacement)
                pending.changed("localhost")
                assertFails { client.get(endpoint.url) }
                gateways.setClientCert(endpoint.url, alias)
                pending.changed("localhost")
                assertEquals(200, client.get(endpoint.url).status.value)
            } finally { client.close() }
        }
    }

    @Test fun hostnameSelectionUsesTheFirstSavedAliasAcrossPorts() = runBlocking {
        Endpoint().use { first -> Endpoint().use { second ->
            val store = InMemoryKeyValueStore()
            val identities = LinuxClientCertificates(store)
            val firstAlias = identities.import("localhost", first.port, fixtures.bundle(), password)
            val secondAlias = identities.import("localhost", second.port, fixtures.bundle("other"), password)
            val gateways = GatewayRepository(store)
            gateways.save(SavedGateway(first.url + "/first", clientCertAlias = firstAlias))
            gateways.save(SavedGateway(second.url + "/second", clientCertAlias = secondAlias))
            val client = createHttpClient(desktopMtlsEngine(ClientCertAliases(), gateways, identities, trust("server")))
            try {
                assertEquals(200, client.get(first.url).status.value)
                val response = runCatching { client.get(second.url).status.value }
                assertTrue(response.isSuccess, "The first saved hostname identity must also authenticate another port: ${response.exceptionOrNull()?.javaClass?.simpleName}")
                assertEquals(200, response.getOrThrow())
                assertEquals(listOf("CN=Synthetic client"), first.peers.toList())
                assertEquals(listOf("CN=Synthetic client"), second.peers.toList())
            } finally { client.close() }
        } }
    }

    @Test fun aMissingFirstAliasDoesNotFallThroughToAnotherSavedGateway() = runBlocking {
        Endpoint(required = false).use { endpoint ->
            val store = InMemoryKeyValueStore()
            val identities = LinuxClientCertificates(store)
            val usable = identities.import("localhost", endpoint.port, fixtures.bundle(), password)
            val gateways = GatewayRepository(store)
            gateways.save(SavedGateway(endpoint.url + "/first", clientCertAlias = "client-00000000-0000-0000-0000-000000000000"))
            gateways.save(SavedGateway(endpoint.url + "/second", clientCertAlias = usable))
            val client = createHttpClient(desktopMtlsEngine(ClientCertAliases(), gateways, identities, trust("server")))
            try { assertEquals(200, client.get(endpoint.url).status.value) }
            finally { client.close() }
            assertEquals(listOf("none"), endpoint.peers.toList())
        }
    }

    @Test fun pendingPicksFollowHostnameButNotAnotherHostOrRedirect() = runBlocking {
        Endpoint().use { selected -> Endpoint(required = false).use { other ->
            val store = InMemoryKeyValueStore()
            val identities = LinuxClientCertificates(store)
            val pending = ClientCertAliases()
            val gateways = GatewayRepository(store)
            val alias = identities.import("localhost", selected.port, fixtures.bundle(), password)
            gateways.save(SavedGateway(selected.url, clientCertAlias = alias))
            pending.set("localhost", alias)
            val client = createHttpClient(desktopMtlsEngine(pending, gateways, identities, trust("server")))
            try {
                assertEquals(200, client.get(selected.url).status.value)
                assertEquals(200, client.get(other.url).status.value)
                assertEquals(200, client.get(other.url.replace("localhost", "127.0.0.1")).status.value)
                assertEquals(listOf("CN=Synthetic client", "none"), other.peers.toList())
                selected.redirect = other.url
                assertEquals(302, client.get(selected.url).status.value)
                assertEquals(2, other.peers.size)
            } finally { client.close() }
        } }
    }

    @Test fun aSavedHostnameIsSelectedLikeAndroidEvenWhenTheStoredSchemeDiffers() = runBlocking {
        Endpoint(required = false).use { endpoint ->
            val store = InMemoryKeyValueStore()
            val identities = LinuxClientCertificates(store)
            val alias = identities.import("localhost", endpoint.port, fixtures.bundle(), password)
            val gateways = GatewayRepository(store)
            gateways.save(SavedGateway(endpoint.url.replace("https:", "http:"), clientCertAlias = alias))
            val client = createHttpClient(desktopMtlsEngine(ClientCertAliases(), gateways, identities, trust("server")))
            try { assertEquals(200, client.get(endpoint.url).status.value) }
            finally { client.close() }
            assertEquals(listOf("CN=Synthetic client"), endpoint.peers.toList())
        }
    }

    @Test fun aTrustedCertificateForAnotherHostnameIsStillRejected() = runBlocking {
        Endpoint(serverCertificate = "wrong-host-server").use { endpoint ->
            val address = endpoint.url.replace("localhost", "127.0.0.1")
            val store = InMemoryKeyValueStore()
            val identities = LinuxClientCertificates(store)
            val alias = identities.import("127.0.0.1", endpoint.port, fixtures.bundle(), password)
            val gateways = GatewayRepository(store)
            gateways.save(SavedGateway(address, clientCertAlias = alias))
            val client = createHttpClient(desktopMtlsEngine(ClientCertAliases(), gateways, identities, trust("wrong-host-server")))
            try { assertFailsWith<javax.net.ssl.SSLPeerUnverifiedException> { client.get(address) } }
            finally { client.close() }
            assertTrue(endpoint.peers.isEmpty())
        }
    }

    @Test fun anUntrustedServerStillFailsWithASelectedClientIdentity() = runBlocking {
        Endpoint().use { endpoint ->
            val store = InMemoryKeyValueStore()
            val identities = LinuxClientCertificates(store)
            val alias = identities.import("localhost", endpoint.port, fixtures.bundle(), password)
            val gateways = GatewayRepository(store)
            gateways.save(SavedGateway(endpoint.url, clientCertAlias = alias))
            val client = createHttpClient(desktopMtlsEngine(ClientCertAliases(), gateways, identities, trust("other")))
            try { assertFails { client.get(endpoint.url) } }
            finally { client.close() }
            assertTrue(endpoint.peers.isEmpty())
        }
    }

    private inner class Endpoint(required: Boolean = true, private val keepAlive: Boolean = false, serverCertificate: String = "server") : AutoCloseable {
        val peers = ConcurrentLinkedQueue<String>()
        @Volatile var redirect: String? = null
        private val source = fixtures.fixture()
        private val keys = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("server", source.getKey("server", password), password, arrayOf(source.getCertificate(serverCertificate)))
        }
        private val server = SSLContext.getInstance("TLS").run {
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
            init(km.keyManagers, arrayOf(trust("client")), null)
            serverSocketFactory.createServerSocket(0, 10, InetAddress.getLoopbackAddress()) as SSLServerSocket
        }.apply {
            if (required) needClientAuth = true else wantClientAuth = true
        }
        val port: Int get() = server.localPort
        val url: String get() = "https://localhost:$port"
        private val sockets = ConcurrentLinkedQueue<SSLSocket>()
        private val sessions = ConcurrentLinkedQueue<Thread>()
        private val worker = thread(isDaemon = true, name = "synthetic-mtls") {
            while (!server.isClosed) {
                val socket = try { server.accept() as SSLSocket } catch (_: java.io.IOException) { break }
                sockets.add(socket)
                sessions.add(thread(isDaemon = true, name = "synthetic-mtls-session") { socket.use {
                    socket.soTimeout = 5000
                    try {
                        socket.startHandshake()
                        val peer = runCatching { socket.session.peerCertificates.first() as X509Certificate }.getOrNull()
                        val reader = socket.inputStream.bufferedReader()
                        while (true) {
                            val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.take(100).toList()
                            if (headers.isEmpty()) break
                            peers.add(peer?.subjectX500Principal?.name ?: "none")
                            val key = headers.firstOrNull { it.startsWith("Sec-WebSocket-Key:", true) }?.substringAfter(':')?.trim()
                            val output = socket.outputStream
                            if (key != null) {
                                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                                output.write(byteArrayOf(0x81.toByte(), 7) + "fixture".toByteArray() + byteArrayOf(0x88.toByte(), 0))
                            } else {
                                val target = redirect
                                val response = if (target == null) "200 OK" else "302 Found\r\nLocation: $target"
                                output.write("HTTP/1.1 $response\r\nContent-Length: 0\r\nConnection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n".toByteArray())
                            }
                            output.flush()
                            if (key != null || !keepAlive) break
                        }
                    } catch (_: java.io.IOException) { /* Rejected handshakes are assertions at the client. */ }
                } })
            }
        }
        override fun close() {
            server.close()
            worker.join(6000)
            sockets.forEach { it.close() }
            sessions.forEach { it.join(6000); check(!it.isAlive) }
            check(!worker.isAlive)
        }
    }
}

package dev.hermeskotlin.core.media

import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.request.get
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicWebEngineTest {
    private fun refused(block: suspend () -> Unit): Boolean = runBlocking {
        try {
            block()
            false
        } catch (e: Exception) {
            generateSequence<Throwable>(e) { it.cause }.any { it is NotPublicAddress }
        }
    }

    @Test fun namesResolvingHomeAreRefusedBeforeConnecting() {
        val client = createHttpClient(publicWebEngine())
        assertTrue(refused { client.get("http://localhost:1/") })
    }

    @Test fun addressLiteralsNeverGetARequest() {
        // OkHttp connects to an address-like host without asking Dns, so the request must stop after connecting.
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val received = AtomicBoolean(false)
            val listener = thread {
                runCatching { server.accept().use { if (it.getInputStream().read() >= 0) received.set(true) } }
            }
            val client = createHttpClient(publicWebEngine())
            assertTrue(refused { client.get("http://127.1:${server.localPort}/admin") })
            server.close()
            listener.join(2_000)
            assertFalse(received.get())
        }
    }
}

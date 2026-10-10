package dev.hermeskotlin.core.auth

import io.ktor.http.parseQueryString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.IPPROTO_TCP
import platform.posix.POLLIN
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.accept
import platform.posix.bind
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * A one-request HTTP listener on 127.0.0.1 for the browser sign-in, as on Android: the gateway only accepts a
 * loopback IP literal as the redirect (RFC 8252 §7.3). The sign-in page runs in an in-app browser session, so
 * the app stays in front and this listener keeps running. The browser is answered with a redirect to
 * [returnUri], which the session watches for, so the sheet closes and Herald is back.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLoopbackReceiver(private val returnUri: String) : LoopbackReceiver {

    override suspend fun start(): LoopbackListener = withContext(Dispatchers.IO) {
        val fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        check(fd >= 0) { "No socket for the sign-in." }
        val port = try {
            memScoped {
                val address = alloc<sockaddr_in>().apply {
                    sin_len = sizeOf<sockaddr_in>().convert()
                    sin_family = AF_INET.convert()
                    sin_port = 0u
                    sin_addr.s_addr = hostToNetwork(LOOPBACK_ADDRESS)
                }
                check(bind(fd, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) { "Couldn't open a port for the sign-in." }
                check(listen(fd, BACKLOG) == 0) { "Couldn't listen for the sign-in." }
                val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                check(getsockname(fd, address.ptr.reinterpret(), length.ptr) == 0) { "Couldn't read the sign-in port." }
                networkToHost(address.sin_port)
            }
        } catch (e: Exception) {
            platform.posix.close(fd)
            throw e
        }
        Listener(fd, port, returnUri)
    }

    private class Listener(private val fd: Int, port: Int, private val returnUri: String) : LoopbackListener {

        override val redirectUri = "http://$LOOPBACK:$port$PATH"

        override suspend fun awaitCallback(): Map<String, String> = withContext(Dispatchers.IO) {
            var query: Map<String, String>? = null
            while (query == null) {
                // A short wait for a connection, so a cancelled sign-in (the user gave up) stops listening promptly.
                ensureActive()
                if (!readable(fd, ACCEPT_TIMEOUT_MS)) continue
                val client = accept(fd, null, null)
                if (client < 0) continue
                query = try {
                    answer(client)
                } finally {
                    platform.posix.close(client)
                }
            }
            query
        }

        /** The callback's query, or null for anything else the browser asks for (a favicon) or a dropped connection. */
        private fun answer(client: Int): Map<String, String>? {
            memScoped {
                val on = alloc<IntVar>().apply { value = 1 }
                setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, on.ptr, sizeOf<IntVar>().convert())
            }
            val head = readHead(client) ?: return null
            val requestLine = head.substringBefore("\r\n")
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            if (!requestLine.startsWith("GET ") || target.substringBefore('?') != PATH) {
                write(client, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                return null
            }
            val body = ("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
                "<title>Signed in</title><p style=\"font-family:sans-serif;padding:24px\">Signed in. " +
                "<a href=\"$returnUri\">Go back to Herald</a></p>").encodeToByteArray()
            write(
                client,
                ("HTTP/1.1 302 Found\r\nLocation: $returnUri\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").encodeToByteArray() + body,
            )
            val parameters = parseQueryString(target.substringAfter('?', ""))
            return parameters.names().associateWith { parameters[it].orEmpty() }
        }

        /** The request line and headers, up to the blank line; null when the browser goes quiet or hangs up first. */
        private fun readHead(client: Int): String? {
            val received = StringBuilder()
            val buffer = ByteArray(1024)
            while (!received.contains("\r\n\r\n")) {
                // Short: a browser's idle preconnect would otherwise hold the callback up behind it.
                if (!readable(client, READ_TIMEOUT_MS)) return null
                val count = buffer.usePinned { recv(client, it.addressOf(0), buffer.size.convert(), 0) }.toInt()
                if (count <= 0) return null
                // Latin-1, as HTTP heads are: every byte is one character.
                for (i in 0 until count) received.append((buffer[i].toInt() and 0xFF).toChar())
                if (received.length > MAX_HEAD) return null
            }
            return received.toString()
        }

        private fun write(client: Int, bytes: ByteArray) {
            var sent = 0
            bytes.usePinned { pinned ->
                while (sent < bytes.size) {
                    val n = send(client, pinned.addressOf(sent), (bytes.size - sent).convert(), 0).toInt()
                    if (n <= 0) return
                    sent += n
                }
            }
        }

        override fun close() {
            platform.posix.close(fd)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PATH = "/callback"
        const val BACKLOG = 4
        const val ACCEPT_TIMEOUT_MS = 500
        const val READ_TIMEOUT_MS = 2_000
        const val MAX_HEAD = 16 * 1024
    }
}

/** Whether [fd] has something to read (a connection to accept, bytes to receive) within [timeoutMs]. */
@OptIn(ExperimentalForeignApi::class)
private fun readable(fd: Int, timeoutMs: Int): Boolean = memScoped {
    val entry = alloc<pollfd>().apply {
        this.fd = fd
        events = POLLIN.convert()
    }
    poll(entry.ptr, 1u, timeoutMs) > 0
}

/** 127.0.0.1 in host order. */
private const val LOOPBACK_ADDRESS = 0x7F000001u

// Apple's CPUs are little-endian, and sockets want ports and addresses big-endian.
private fun hostToNetwork(value: UInt): UInt =
    ((value and 0xFFu) shl 24) or ((value and 0xFF00u) shl 8) or ((value shr 8) and 0xFF00u) or (value shr 24)

private fun networkToHost(port: UShort): Int = ((port.toInt() and 0xFF) shl 8) or ((port.toInt() shr 8) and 0xFF)

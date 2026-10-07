package dev.hermeskotlin.core.auth

import io.ktor.http.parseQueryString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A one-request HTTP listener on 127.0.0.1 for the browser sign-in. The gateway only accepts a loopback IP
 * literal as the redirect (RFC 8252 §7.3). The browser is answered with a redirect to [returnUri], a link only
 * this app opens, so it closes the browser tab and brings Herald back.
 */
class AndroidLoopbackReceiver(private val returnUri: String) : LoopbackReceiver {

    override suspend fun start(): LoopbackListener = withContext(Dispatchers.IO) {
        Listener(ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK)), returnUri)
    }

    private class Listener(private val server: ServerSocket, private val returnUri: String) : LoopbackListener {

        override val redirectUri = "http://$LOOPBACK:${server.localPort}$PATH"

        override suspend fun awaitCallback(): Map<String, String> = withContext(Dispatchers.IO) {
            // A short accept timeout, so a cancelled wait (the user gave up) stops listening promptly.
            server.soTimeout = ACCEPT_TIMEOUT_MS
            var query: Map<String, String>? = null
            while (query == null) {
                ensureActive()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                // A connection the browser drops halfway (a preconnect) isn't the callback; keep listening.
                query = try {
                    socket.use(::answer)
                } catch (_: IOException) {
                    null
                }
            }
            query
        }

        /** The callback's query, or null for anything else the browser asks for (a favicon). */
        private fun answer(socket: Socket): Map<String, String>? {
            socket.soTimeout = READ_TIMEOUT_MS
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = try {
                reader.readLine()
            } catch (_: SocketTimeoutException) {
                null
            } ?: return null
            // The headers say nothing the callback needs; read past them so the browser sees a whole exchange.
            while (reader.readLine()?.isNotEmpty() == true) Unit
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            val out = socket.getOutputStream()
            if (!requestLine.startsWith("GET ") || target.substringBefore('?') != PATH) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return null
            }
            val body = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
                "<title>Signed in</title><p style=\"font-family:sans-serif;padding:24px\">Signed in. " +
                "<a href=\"$returnUri\">Go back to Herald</a></p>"
            val bytes = body.toByteArray()
            out.write(
                ("HTTP/1.1 302 Found\r\nLocation: $returnUri\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
            val parameters = parseQueryString(target.substringAfter('?', ""))
            return parameters.names().associateWith { parameters[it].orEmpty() }
        }

        override fun close() = server.close()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PATH = "/callback"
        const val BACKLOG = 4
        const val ACCEPT_TIMEOUT_MS = 500
        /** Short: a browser's idle preconnect would otherwise hold the callback up behind it. */
        const val READ_TIMEOUT_MS = 2_000
    }
}

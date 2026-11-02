package dev.hermeskotlin.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * One Herald per user, as Hermes Desktop: a second launch hands over to the running one, which comes to the
 * front, and quits. The first holds a lock file in its folder and listens on a loopback port written next to it.
 */
class SingleInstance private constructor(@Suppress("unused") private val lock: FileLock?, private val server: ServerSocket?) {

    /**
     * Calls [onShow] (on its own thread) each time another launch asks this one to come forward, with the link that
     * launch was opened with (a `herald://` link from a notification or a browser), if any.
     */
    fun listen(onShow: (link: String?) -> Unit) {
        val server = server ?: return
        thread(name = "herald-instance", isDaemon = true) {
            while (!server.isClosed) {
                // A caller that connects and says nothing gives up after a second, so it can't hold up the next "show".
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 1_000
                        val line = socket.getInputStream().bufferedReader().readLine()
                        when {
                            line == SHOW -> onShow(null)
                            line?.startsWith("$OPEN ") == true -> onShow(line.removePrefix("$OPEN "))
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val SHOW = "show"
        private const val OPEN = "open"

        /**
         * This launch's claim, or null when another Herald runs and has been asked to come forward (and to open
         * [link], when given). A holder that doesn't answer may be on its way out, so this one runs then too, only
         * without the claim.
         */
        fun claim(dir: File, link: String? = null): SingleInstance? {
            dir.mkdirs()
            val portFile = File(dir, "instance.port")
            val channel = RandomAccessFile(File(dir, "instance.lock"), "rw").channel
            val lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock == null) {
                channel.close()
                val port = portFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
                val reached = port != null && runCatching {
                    // One line: a link can't carry a line break into the next message.
                    val message = link?.takeIf { '\n' !in it && '\r' !in it }?.let { "$OPEN $it" } ?: SHOW
                    Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write("$message\n".toByteArray()) }
                }.isSuccess
                return if (reached) null else SingleInstance(null, null)
            }
            val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            portFile.writeText(server.localPort.toString())
            return SingleInstance(lock, server)
        }
    }
}

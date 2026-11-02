package dev.hermeskotlin.core.storage

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.TimeUnit

/** A bounded anonymous pipe; neither endpoint may survive an exec. */
internal class SecretPipe : AutoCloseable {
    private val descriptors = IntArray(2)
    private var writerOpen = true
    val output: Int get() = descriptors[1]

    init {
        if (libc.pipe2(descriptors, 0x80000) != 0) throw SecretPortalFailure(SecretPortalFailure.Reason.Failed)
    }

    fun closeWriter() {
        if (writerOpen) {
            writerOpen = false
            if (libc.close(output) != 0) fail()
        }
    }

    fun read(timeoutMillis: Long): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        Memory(4097).use { bytes ->
            Memory(8).use { poll ->
                poll.setInt(0, descriptors[0])
                poll.setShort(4, 1)
                var total = 0
                try {
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        if (System.nanoTime() - deadline > 0) throw SecretPortalFailure(SecretPortalFailure.Reason.Failed)
                        val ready = libc.poll(poll, 1, 100)
                        // A signal (the JVM's own, for one) can cut a wait short; the secret is still in the pipe.
                        if (ready == 0 || ready < 0 && Native.getLastError() == EINTR) continue
                        if (ready < 0 || poll.getShort(6).toInt() and 32 != 0) fail()
                        val count = libc.read(descriptors[0], bytes.share(total.toLong()), 4097L - total).toInt()
                        if (count < 0 && Native.getLastError() == EINTR) continue
                        if (count < 0) fail()
                        if (count == 0) {
                            if (total == 0) throw SecretPortalFailure(SecretPortalFailure.Reason.InvalidSecret)
                            return bytes.getByteArray(0, total)
                        }
                        total += count
                        if (total > 4096) throw SecretPortalFailure(SecretPortalFailure.Reason.InvalidSecret)
                    }
                } finally { bytes.clear() }
            }
        }
    }

    override fun close() {
        try { closeWriter() } finally { if (libc.close(descriptors[0]) != 0) fail() }
    }

    private fun fail(): Nothing = throw SecretPortalFailure(SecretPortalFailure.Reason.Failed)

    private companion object {
        const val EINTR = 4
        val libc: LibC by lazy { Native.load("c", LibC::class.java) }
    }

    internal interface LibC : Library {
        fun pipe2(descriptors: IntArray, flags: Int): Int
        fun close(fd: Int): Int
        fun poll(descriptor: Pointer, count: Long, timeout: Int): Int
        fun read(fd: Int, bytes: Pointer, length: Long): Long
    }
}

package dev.hermeskotlin.ui.components

import dev.hermeskotlin.core.gateway.LinuxClientCertificates
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

internal class ClientCertificateFileException : IOException(
    "Choose a regular PKCS#12 file. Symbolic links, devices and named pipes cannot be imported.",
)

/** Reject nonregular selections; call on an interruptible IO thread so cancellation closes the channel. */
internal fun readClientCertificate(file: File): ByteArray {
    val path = file.toPath()
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw ClientCertificateFileException()
    return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        val buffer = ByteBuffer.allocate(LinuxClientCertificates.MAX_BYTES + 1)
        try {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) Unit
            buffer.array().copyOf(buffer.position())
        } finally { buffer.array().fill(0) }
    }
}

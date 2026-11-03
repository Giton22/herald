package dev.hermeskotlin.core.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Reads this stream as an [AttachmentReader], off the main thread, and closes it with the reader. */
fun InputStream.attachmentReader(): AttachmentReader = object : AttachmentReader {
    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { this@attachmentReader.read(buffer) }

    override fun close() = this@attachmentReader.close()
}

/** A source of [size] bytes that opens a fresh stream for each read; [open] may block, and runs off the main thread. */
fun streamedSource(size: Long, open: () -> InputStream): AttachmentSource =
    AttachmentSource(size) { withContext(Dispatchers.IO) { open() }.attachmentReader() }

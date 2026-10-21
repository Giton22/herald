package dev.hermeskotlin.ui.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.hermeskotlin.core.media.FileText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer

private const val TEXT_PREVIEW_BYTES = 512 * 1024
private const val SAVED_FILE_READ_ERROR = "Couldn't read this saved file. It may have been moved or deleted."

/** Reads only the saved URI, never the gateway copy. */
internal suspend fun readSavedText(resolver: ContentResolver, uri: Uri): FileLoad = withContext(Dispatchers.IO) {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext FileLoad.Failed(SAVED_FILE_READ_ERROR)
    try {
        val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException()
        descriptor.use {
            val size = descriptor.length.takeIf { it >= 0 } ?: savedFileSize(resolver, uri)
            val bytes = descriptor.createInputStream().use { it.readBounded(TEXT_PREVIEW_BYTES.toLong() + 1) }
            val truncated = bytes.size > TEXT_PREVIEW_BYTES
            val text = decodeSavedText(bytes, truncated)
            FileLoad.Ready(FileText(text.orEmpty(), size ?: bytes.size.toLong(), truncated, binary = text == null))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        FileLoad.Failed(SAVED_FILE_READ_ERROR)
    }
}

internal suspend fun readSavedBytes(
    resolver: ContentResolver,
    uri: Uri,
    maxBytes: Int = 32 * 1024 * 1024,
): FileDownload = withContext(Dispatchers.IO) {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || maxBytes < 0) return@withContext FileDownload.Failed(SAVED_FILE_READ_ERROR)
    val tooLarge = FileDownload.Failed("This saved file is too large to open here. Try another app.")
    try {
        val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException()
        descriptor.use {
            val size = descriptor.length.takeIf { it >= 0 } ?: savedFileSize(resolver, uri)
            if (size != null && size > maxBytes) return@withContext tooLarge
            val bytes = descriptor.createInputStream().use { it.readBounded(maxBytes.toLong() + 1) }
            if (bytes.size > maxBytes) tooLarge else FileDownload.Ready(bytes)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        FileDownload.Failed(SAVED_FILE_READ_ERROR)
    }
}

private fun savedFileSize(resolver: ContentResolver, uri: Uri): Long? = try {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column).takeIf { it >= 0 } else null
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null // Providers aren't required to supply a size.
}

private suspend fun InputStream.readBounded(limit: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var remaining = limit
    while (remaining > 0) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) break
        if (count == 0) {
            val byte = read()
            if (byte < 0) break
            output.write(byte)
            remaining--
        } else {
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
    currentCoroutineContext().ensureActive()
    return output.toByteArray()
}

private fun decodeSavedText(bytes: ByteArray, truncated: Boolean): String? {
    val length = minOf(bytes.size, TEXT_PREVIEW_BYTES)
    fun startsWith(vararg prefix: Int) = length >= prefix.size && prefix.indices.all { bytes[it].toInt() and 0xff == prefix[it] }
    if (startsWith(0x50, 0x4b, 3, 4) || startsWith(0x50, 0x4b, 5, 6) || startsWith(0x50, 0x4b, 7, 8) ||
        startsWith(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) return null
    val (charset, offset) = when {
        startsWith(0xef, 0xbb, 0xbf) -> Charsets.UTF_8 to 3
        startsWith(0xff, 0xfe) -> Charsets.UTF_16LE to 2
        startsWith(0xfe, 0xff) -> Charsets.UTF_16BE to 2
        else -> Charsets.UTF_8 to 0
    }
    val input = ByteBuffer.wrap(bytes, offset, length - offset)
    val output = CharBuffer.allocate(length)
    // An incomplete character at the preview boundary is omitted, not replaced or treated as binary.
    if (charset.newDecoder().decode(input, output, !truncated).isError) return null
    output.flip()
    val text = output.toString()
    return text.takeUnless { value -> value.any { it.isISOControl() && it !in "\t\r\n\u000c" } }
}

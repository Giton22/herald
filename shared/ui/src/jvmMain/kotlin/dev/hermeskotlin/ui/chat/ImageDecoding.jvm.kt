package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.CRC32
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import kotlin.math.max
import kotlin.math.roundToInt

// Small pictures (avatars, thumbnails) are drawn at once; a cache keeps a transcript scrolled back over its
// pictures from decoding each again as its row comes back.
@Composable
actual fun rememberImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = remember(bytes, maxEdge) {
    DecodedCache.decode(bytes, maxEdge)
}

actual fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = decodeScaled(bytes, maxEdge)?.asComposeImageBitmap()

// A picture in the transcript or the viewer decodes off the UI thread, so a chat of photos opens and scrolls
// without a stall; one decoded before shows at once. TIFF Skia doesn't read at all.
@Composable
actual fun rememberDecodedImage(bytes: ByteArray, maxEdge: Int): DecodedImage {
    val cached = remember(bytes, maxEdge) { DecodedCache.peek(bytes, maxEdge) }
    if (cached != null) return DecodedImage(cached, decoding = false)
    return produceState(DecodedImage(null, decoding = true), bytes, maxEdge) {
        value = DecodedImage(withContext(Dispatchers.Default) { DecodedCache.decode(bytes, maxEdge) }, decoding = false)
    }.value
}

/** Decoded pictures by their bytes and size, least recently used first out, up to [LIMIT] of pixels. */
private object DecodedCache {
    private const val LIMIT = 64L shl 20

    /** A picture bigger than this (the viewer's full size) isn't kept: it would push out all the rest. */
    private const val LARGEST = 16L shl 20

    /** Two hashes, so two pictures of one length that meet on one hash still don't show as each other. */
    private data class Key(val hash: Int, val crc: Long, val size: Int, val maxEdge: Int)

    private val entries = LinkedHashMap<Key, ImageBitmap>(16, 0.75f, true)
    private var held = 0L

    private fun key(bytes: ByteArray, maxEdge: Int) =
        Key(bytes.contentHashCode(), CRC32().apply { update(bytes) }.value, bytes.size, maxEdge)

    private val ImageBitmap.cost: Long get() = width.toLong() * height * 4

    @Synchronized
    fun peek(bytes: ByteArray, maxEdge: Int): ImageBitmap? = entries[key(bytes, maxEdge)]

    fun decode(bytes: ByteArray, maxEdge: Int): ImageBitmap? {
        val key = key(bytes, maxEdge)
        synchronized(this) { entries[key]?.let { return it } }
        val bitmap = decodeScaled(bytes, maxEdge)?.asComposeImageBitmap() ?: return null
        if (bitmap.cost <= LARGEST) synchronized(this) {
            entries.put(key, bitmap)?.let { held -= it.cost }
            held += bitmap.cost
            val oldest = entries.entries.iterator()
            while (held > LIMIT && oldest.hasNext()) {
                val entry = oldest.next()
                if (entry.key == key) continue
                held -= entry.value.cost
                oldest.remove()
            }
        }
        return bitmap
    }
}

/** [bytes] decoded by Skia, at most [maxEdge] px on its long side; null when Skia can't read it. */
internal fun decodeScaled(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
    val scale = minOf(1f, maxEdge.toFloat() / max(image.width, image.height))
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    // The native image and canvas are let go here, not left to the garbage collector.
    return image.use {
        val bitmap = Bitmap()
        if (!bitmap.allocN32Pixels(width, height)) {
            bitmap.close()
            return@use null
        }
        // Linear filtering with mipmaps: a photo scaled far down stays smooth rather than jagged.
        Canvas(bitmap).use { canvas ->
            canvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                null,
                true,
            )
        }
        bitmap.setImmutable()
        bitmap
    }
}

/** [this] as a JPEG at [quality]. */
internal fun Bitmap.jpeg(quality: Int): ByteArray? =
    Image.makeFromBitmap(this).use { it.encodeToData(EncodedImageFormat.JPEG, quality)?.bytes }

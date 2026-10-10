package dev.hermeskotlin.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An encoded picture decoded upright and scaled down so its long side is at most [maxEdge] px; null when it
 * can't be read. Skia's decoder already applies the EXIF orientation, so the size it reports is the upright one.
 */
internal fun decodeUpright(bytes: ByteArray, maxEdge: Int): Image? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    val scale = minOf(1f, maxEdge.toFloat() / max(image.width, image.height))
    if (scale == 1f) return@runCatching image
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(width.toFloat(), height.toFloat()),
        SamplingMode.LINEAR,
        null,
        true,
    )
    surface.makeImageSnapshot()
}.getOrNull()

internal fun Image.toBitmap(): ImageBitmap = toComposeImageBitmap()

/** JPEG bytes of this picture at [quality] (0–100). */
internal fun Image.jpeg(quality: Int): ByteArray =
    encodeToData(EncodedImageFormat.JPEG, quality)?.bytes ?: error("Couldn't encode the picture.")

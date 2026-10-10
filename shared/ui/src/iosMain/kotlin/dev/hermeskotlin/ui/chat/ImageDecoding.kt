package dev.hermeskotlin.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An encoded picture decoded upright (its EXIF rotation applied) and scaled down so its long side is at most
 * [maxEdge] px; null when it can't be read.
 */
internal fun decodeUpright(bytes: ByteArray, maxEdge: Int): Image? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    val origin = runCatching { Codec.makeFromData(Data.makeFromBytes(bytes)).encodedOrigin }.getOrDefault(EncodedOrigin.TOP_LEFT)
    val turned = origin == EncodedOrigin.RIGHT_TOP || origin == EncodedOrigin.LEFT_BOTTOM
    val uprightWidth = if (turned) image.height else image.width
    val uprightHeight = if (turned) image.width else image.height
    val scale = minOf(1f, maxEdge.toFloat() / max(uprightWidth, uprightHeight))
    if (scale == 1f && origin == EncodedOrigin.TOP_LEFT) return@runCatching image
    val width = (uprightWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (uprightHeight * scale).roundToInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.apply {
        // Turn the canvas so the stored picture, drawn at its stored size, lands upright.
        when (origin) {
            EncodedOrigin.RIGHT_TOP -> translate(width.toFloat(), 0f).rotate(90f)
            EncodedOrigin.BOTTOM_RIGHT -> translate(width.toFloat(), height.toFloat()).rotate(180f)
            EncodedOrigin.LEFT_BOTTOM -> translate(0f, height.toFloat()).rotate(270f)
            else -> Unit
        }
        val stored = if (turned) Rect.makeWH(height.toFloat(), width.toFloat()) else Rect.makeWH(width.toFloat(), height.toFloat())
        drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), stored, SamplingMode.LINEAR, null, true)
    }
    surface.makeImageSnapshot()
}.getOrNull()

internal fun Image.toBitmap(): ImageBitmap = toComposeImageBitmap()

/** JPEG bytes of this picture at [quality] (0–100). */
internal fun Image.jpeg(quality: Int): ByteArray =
    encodeToData(EncodedImageFormat.JPEG, quality)?.bytes ?: error("Couldn't encode the picture.")

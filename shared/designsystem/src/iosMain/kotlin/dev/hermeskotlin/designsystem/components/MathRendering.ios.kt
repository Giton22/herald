package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * The TeX typesetter on iOS: SwiftMath (an iosMath port with its own math fonts) in the Swift host, which
 * hands a formula back as a PNG. Null until the host sets it at launch: formulas then show as their source.
 */
object IosMath {
    var typeset: ((latex: String, textSizePx: Float, argb: Int, display: Boolean) -> NSData?)? = null
}

internal actual fun typesetMath(latex: String, textSizePx: Float, argb: Int, display: Boolean): ImageBitmap? {
    val png = IosMath.typeset?.invoke(latex, textSizePx, argb, display)?.toByteArray() ?: return null
    val image = Image.makeFromEncoded(png)
    // A runaway formula shouldn't hold a huge bitmap.
    if (image.width <= 0 || image.height <= 0 || image.width.toLong() * image.height > MAX_PIXELS) return null
    return image.toComposeImageBitmap()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}

private const val MAX_PIXELS = 4096L * 2048L

package dev.hermeskotlin.ui.platform

import dev.hermeskotlin.core.platform.toByteArray
import dev.hermeskotlin.core.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An encoded picture as UIKit reads it: HEIC from the camera as well as JPEG and PNG, with its orientation
 * known. Null when it isn't a picture.
 */
internal fun uiImage(bytes: ByteArray): UIImage? = UIImage.imageWithData(bytes.toNSData())

/** This picture drawn upright at most [maxEdge] px on its long side (it was turned by its orientation). */
@OptIn(ExperimentalForeignApi::class)
internal fun UIImage.uprightScaled(maxEdge: Int): UIImage {
    val (width, height) = size.useContents { width * scale to height * scale }
    val factor = min(1.0, maxEdge / max(width, height))
    val targetWidth = (width * factor).roundToInt().coerceAtLeast(1).toDouble()
    val targetHeight = (height * factor).roundToInt().coerceAtLeast(1).toDouble()
    // One pixel per point, so the size above is the size in pixels.
    val format = UIGraphicsImageRendererFormat.preferredFormat().apply { scale = 1.0 }
    return UIGraphicsImageRenderer(size = CGSizeMake(targetWidth, targetHeight), format = format).imageWithActions {
        drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
    }
}

/** JPEG bytes of this picture at [quality] (0–100). */
internal fun UIImage.jpeg(quality: Int): ByteArray =
    UIImageJPEGRepresentation(this, quality / 100.0)?.toByteArray() ?: error("Couldn't encode the picture.")

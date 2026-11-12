package dev.hermeskotlin.ui.bots

import androidx.compose.runtime.Composable

/**
 * Opens the platform's photo picker for a bot's picture. The chosen photo arrives in [onPicked] upright, cut to
 * the square at its middle and scaled to [BOT_PHOTO_EDGE] px, as a PNG ready for `profiles.set_asset`; [onError]
 * gets a sentence when it can't be read.
 */
@Composable
expect fun rememberBotPhotoPicker(onPicked: (ByteArray) -> Unit, onError: (String) -> Unit): () -> Unit

/** The side of the square a bot's picture is kept at: the size Desktop's uploads are, well under the gateway's 2 MB. */
const val BOT_PHOTO_EDGE = 256

/** The original photo read to be cut down: any phone camera's photo fits. */
internal const val MAX_BOT_PHOTO_BYTES = 50L * 1024 * 1024

/** The square at the middle of a [width] by [height] picture, as its left, top and side. */
internal fun centreSquare(width: Int, height: Int): Triple<Int, Int, Int> {
    val side = minOf(width, height)
    return Triple((width - side) / 2, (height - side) / 2, side)
}

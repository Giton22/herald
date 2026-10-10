package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.ui.chat.decodeUpright
import dev.hermeskotlin.ui.chat.toBitmap
import dev.hermeskotlin.ui.platform.jpeg
import dev.hermeskotlin.ui.platform.pickPhotos
import dev.hermeskotlin.ui.platform.uiImage
import dev.hermeskotlin.ui.platform.uprightScaled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberWallpaperPicker(onPicked: (ByteArray) -> Unit, onError: (String) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    return remember {
        {
            scope.launch {
                val photo = pickPhotos(limit = 1).firstOrNull() ?: return@launch
                val image = withContext(Dispatchers.Default) {
                    runCatching {
                        if (photo.bytes.size > MAX_PHOTO_BYTES) error("That photo is too large or can't be read.")
                        // Kept upright and screen-sized, so showing it later is a plain decode.
                        val decoded = uiImage(photo.bytes) ?: error("That file isn't an image Herald can read.")
                        decoded.uprightScaled(WALLPAPER_EDGE).jpeg(WALLPAPER_QUALITY)
                    }
                }
                image.onSuccess(picked).onFailure { failed(it.message ?: "Couldn't read that photo.") }
            }
        }
    }
}

@Composable
actual fun rememberWallpaperBitmap(image: ByteArray?): ImageBitmap? = produceState<ImageBitmap?>(null, image) {
    value = image?.let { withContext(Dispatchers.Default) { decodeUpright(it, WALLPAPER_EDGE)?.toBitmap() } }
}.value

/** Long edge of the kept image: a tablet screen's worth, so it stays sharp when cropped to fill. */
private const val WALLPAPER_EDGE = 2560
private const val WALLPAPER_QUALITY = 90

/** An original photo read to be resized: any phone camera's photo fits. */
private const val MAX_PHOTO_BYTES = 50L * 1024 * 1024

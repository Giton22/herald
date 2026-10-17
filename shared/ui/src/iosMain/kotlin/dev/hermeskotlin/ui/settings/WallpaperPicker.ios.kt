package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.ui.chat.decodeUpright
import dev.hermeskotlin.ui.chat.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberWallpaperPicker(onPicked: (ByteArray) -> Unit, onError: (String) -> Unit): () -> Unit {
    val error by rememberUpdatedState(onError)
    return remember { { error("Choosing a background isn't available on iOS yet.") } }
}

@Composable
actual fun rememberWallpaperBitmap(image: ByteArray?): ImageBitmap? = produceState<ImageBitmap?>(null, image) {
    value = image?.let { withContext(Dispatchers.Default) { decodeUpright(it, WALLPAPER_EDGE)?.toBitmap() } }
}.value

/** About a phone screen's long side, so the background stays sharp without holding a full camera photo. */
private const val WALLPAPER_EDGE = 2_400

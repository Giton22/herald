package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.hermeskotlin.ui.chat.IMAGE_EXTENSIONS
import dev.hermeskotlin.ui.chat.decodeScaled
import dev.hermeskotlin.ui.chat.fromDialog
import dev.hermeskotlin.ui.chat.jpeg
import dev.hermeskotlin.ui.chat.openFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberWallpaperPicker(onPicked: (ByteArray) -> Unit, onError: (String) -> Unit): () -> Unit {
    val picked = rememberUpdatedState(onPicked)
    val failed = rememberUpdatedState(onError)
    val scope = rememberCoroutineScope()
    return remember {
        {
            scope.launch {
                val file = fromDialog { openFiles("Chat background", multiple = false, IMAGE_EXTENSIONS) }
                    .getOrElse { return@launch failed.value("Couldn't open the file dialog.") }
                    .firstOrNull() ?: return@launch
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { file.takeIf { it.length() <= MAX_PHOTO_BYTES }?.readBytes()?.let { decodeScaled(it, WALLPAPER_EDGE) }?.jpeg(WALLPAPER_QUALITY) }.getOrNull()
                }
                if (bytes != null) picked.value(bytes) else failed.value("Couldn't read that picture.")
            }
        }
    }
}

@Composable
actual fun rememberWallpaperBitmap(image: ByteArray?): ImageBitmap? = produceState<ImageBitmap?>(null, image) {
    value = image?.let { withContext(Dispatchers.Default) { decodeScaled(it, WALLPAPER_EDGE)?.asComposeImageBitmap() } }
}.value

private const val WALLPAPER_EDGE = 2560
private const val WALLPAPER_QUALITY = 90
private const val MAX_PHOTO_BYTES = 50L * 1024 * 1024

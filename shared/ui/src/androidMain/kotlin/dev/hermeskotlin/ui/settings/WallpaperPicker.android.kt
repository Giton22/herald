package dev.hermeskotlin.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.hermeskotlin.ui.chat.decodeUpright
import dev.hermeskotlin.ui.chat.jpeg
import dev.hermeskotlin.ui.chat.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** A photo that can't be the wallpaper, said in the user's language. */
private class WallpaperProblem(val words: StringResource) : Exception()

@Composable
actual fun rememberWallpaperPicker(onPicked: (ByteArray) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    val original = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_PHOTO_BYTES) }
                        ?: throw WallpaperProblem(Res.string.android_wallpaper_too_large)
                    // Kept upright and screen-sized, so showing it later is a plain decode.
                    val bitmap = decodeUpright(original, WALLPAPER_EDGE) ?: throw WallpaperProblem(Res.string.android_wallpaper_not_image)
                    bitmap.jpeg(WALLPAPER_QUALITY)
                }
            }
            image.onSuccess(picked)
            when (val e = image.exceptionOrNull()) {
                null -> Unit
                is WallpaperProblem -> failed(getString(e.words))
                else -> failed(e.message ?: getString(Res.string.android_wallpaper_failed))
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
actual fun rememberWallpaperBitmap(image: ByteArray?): ImageBitmap? = produceState<ImageBitmap?>(null, image) {
    value = image?.let { withContext(Dispatchers.Default) { decodeUpright(it, WALLPAPER_EDGE)?.asImageBitmap() } }
}.value

/** Long edge of the kept image: a tablet screen's worth, so it stays sharp when cropped to fill. */
private const val WALLPAPER_EDGE = 2560
private const val WALLPAPER_QUALITY = 90

/** An original photo read to be resized: any phone camera's photo fits. */
private const val MAX_PHOTO_BYTES = 50L * 1024 * 1024

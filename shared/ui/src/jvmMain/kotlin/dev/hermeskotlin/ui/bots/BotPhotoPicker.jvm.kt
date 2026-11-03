package dev.hermeskotlin.ui.bots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import dev.hermeskotlin.ui.chat.IMAGE_EXTENSIONS
import dev.hermeskotlin.ui.chat.decodeScaled
import dev.hermeskotlin.ui.chat.fromDialog
import dev.hermeskotlin.ui.chat.openFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect

@Composable
actual fun rememberBotPhotoPicker(onPicked: (ByteArray) -> Unit, onError: (String) -> Unit): () -> Unit {
    val picked = rememberUpdatedState(onPicked)
    val failed = rememberUpdatedState(onError)
    val scope = rememberCoroutineScope()
    return remember {
        {
            scope.launch {
                val file = fromDialog { openFiles("Bot picture", multiple = false, IMAGE_EXTENSIONS) }
                    .getOrElse { return@launch failed.value("Couldn't open the file dialog.") }
                    .firstOrNull() ?: return@launch
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        file.takeIf { it.length() <= MAX_BOT_PHOTO_BYTES }?.readBytes()
                            ?.let { decodeScaled(it, BOT_PHOTO_EDGE * 2) }
                            ?.let(::squarePng)
                    }.getOrNull()
                }
                if (bytes != null) picked.value(bytes) else failed.value("Couldn't read that picture.")
            }
        }
    }
}

/** The square at the middle of [bitmap], drawn at [BOT_PHOTO_EDGE] px and encoded as a PNG. */
private fun squarePng(bitmap: Bitmap): ByteArray? = bitmap.use { source ->
    val (left, top, side) = centreSquare(source.width, source.height)
    Image.makeFromBitmap(source).use { image ->
        Bitmap().use { square ->
            if (!square.allocN32Pixels(BOT_PHOTO_EDGE, BOT_PHOTO_EDGE)) return@use null
            Canvas(square).use { canvas ->
                canvas.drawImageRect(
                    image,
                    Rect.makeXYWH(left.toFloat(), top.toFloat(), side.toFloat(), side.toFloat()),
                    Rect.makeWH(BOT_PHOTO_EDGE.toFloat(), BOT_PHOTO_EDGE.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                    null,
                    true,
                )
            }
            Image.makeFromBitmap(square).use { it.encodeToData(EncodedImageFormat.PNG)?.bytes }
        }
    }
}

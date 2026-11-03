package dev.hermeskotlin.ui.bots

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.hermeskotlin.ui.chat.decodeUpright
import dev.hermeskotlin.ui.chat.readAtMost
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberBotPhotoPicker(onPicked: (ByteArray) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    val original = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_BOT_PHOTO_BYTES) }
                        ?: error("That photo is too large or can't be read.")
                    // Decoded at twice the kept size, so the square cut from a wide photo stays sharp.
                    val bitmap = decodeUpright(original, BOT_PHOTO_EDGE * 2) ?: error("That file isn't an image Kyllene can read.")
                    val (left, top, side) = centreSquare(bitmap.width, bitmap.height)
                    val square = Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, left, top, side, side), BOT_PHOTO_EDGE, BOT_PHOTO_EDGE, true)
                    ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }
            }
            image.onSuccess(picked).onFailure { failed(it.message ?: "Couldn't read that photo.") }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

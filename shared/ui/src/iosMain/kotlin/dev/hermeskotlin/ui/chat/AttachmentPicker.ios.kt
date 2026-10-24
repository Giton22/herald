package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.core.chat.OutgoingAttachment

@Composable
actual fun rememberAttachmentPicker(
    onPicked: (List<OutgoingAttachment>) -> Unit,
    onError: (String) -> Unit,
): AttachmentPicker {
    val error = rememberUpdatedState(onError)
    return remember {
        object : AttachmentPicker {
            override fun pickPhotos() = error.value(NOT_YET)

            override fun takePhoto() = error.value(NOT_YET)

            override fun pickFiles() = error.value(NOT_YET)
        }
    }
}

@Composable
actual fun rememberImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? =
    remember(bytes, maxEdge) { decodeImageBitmap(bytes, maxEdge) }

actual fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = decodeUpright(bytes, maxEdge)?.toBitmap()

/** Every format iOS decodes reads quickly enough to decode in place, so it's never shown as decoding. */
@Composable
actual fun rememberDecodedImage(bytes: ByteArray, maxEdge: Int): DecodedImage =
    remember(bytes, maxEdge) { DecodedImage(decodeImageBitmap(bytes, maxEdge), decoding = false) }

private const val NOT_YET = "Attachments aren't available on iOS yet."

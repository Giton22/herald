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
    remember(bytes, maxEdge) { decodeUpright(bytes, maxEdge)?.toBitmap() }

private const val NOT_YET = "Attachments aren't available on iOS yet."

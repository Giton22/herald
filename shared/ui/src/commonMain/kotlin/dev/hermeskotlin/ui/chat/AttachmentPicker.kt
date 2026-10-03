package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.core.chat.OutgoingAttachment

/** The platform pickers behind the composer's + button. Results arrive in the callbacks given to [rememberAttachmentPicker]. */
interface AttachmentPicker {
    fun pickPhotos()

    fun takePhoto()

    fun pickFiles()
}

/**
 * Pickers that read what the person chose into [OutgoingAttachment]s (photos scaled down for upload,
 * with a thumbnail) and hand them to [onPicked]; [onError] gets a sentence for anything that failed.
 */
@Composable
expect fun rememberAttachmentPicker(
    onPicked: (List<OutgoingAttachment>) -> Unit,
    onError: (String) -> Unit,
): AttachmentPicker

/** Decodes an encoded image, sampled down to about [maxEdge] px on its long side; null when it can't be read. */
@Composable
expect fun rememberImageBitmap(bytes: ByteArray, maxEdge: Int = 320): ImageBitmap?

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

/** [rememberImageBitmap] outside composition, for decoding off the main thread. */
expect fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap?

/** A picture being decoded: [bitmap] once it's read, null while [decoding] or when it can't be read. */
class DecodedImage(val bitmap: ImageBitmap?, val decoding: Boolean)

/**
 * [rememberImageBitmap] for a file from a reply: a format that decodes slowly (a TIFF on Android) is read in
 * the background, and [DecodedImage.decoding] says so meanwhile, so a view shows a spinner rather than a failure.
 */
@Composable
expect fun rememberDecodedImage(bytes: ByteArray, maxEdge: Int): DecodedImage

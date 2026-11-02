package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.core.chat.OutgoingAttachment

/** The platform pickers behind the composer's + button. Results arrive in the callbacks given to [rememberAttachmentPicker]. */
interface AttachmentPicker {
    fun pickPhotos()

    fun takePhoto()

    fun pickFiles()

    /** Files dropped on the chat, by path or `file:` URI (the desktop's). */
    fun attachFiles(paths: List<String>) {}

    /**
     * Attaches what a paste into the chat brings that isn't text for the composer: copied files, a picture, or text
     * past [LARGE_PASTE] characters, as Hermes Desktop does, as a file. False leaves the paste to the composer.
     */
    fun pasteAttachments(): Boolean = false

    /** The clipboard's text, for a paste with nothing focused; null where there's none (or no desktop). */
    fun clipboardText(): String? = null
}

/** Past this many characters a paste goes along as `pasted.txt` instead of filling the composer. */
const val LARGE_PASTE = 3_000

/** A paste of [text] as the file a large one goes as. */
fun pastedTextAttachment(text: String, id: String): OutgoingAttachment =
    OutgoingAttachment(id, "pasted.txt", "text/plain", text.encodeToByteArray())

/**
 * Where files dropped from the desktop land: [onFiles] gets their paths, and [onHover] whether files are being
 * dragged over it now. Nothing on a phone.
 */
expect fun Modifier.fileDrop(onHover: (Boolean) -> Unit = {}, onFiles: (List<String>) -> Unit): Modifier

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

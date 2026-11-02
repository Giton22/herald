package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import dev.hermeskotlin.core.chat.OutgoingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.random.Random

/** The system's Open dialog for photos and files; photos go out as on Android, scaled and as JPEG. */
@Composable
actual fun rememberAttachmentPicker(
    onPicked: (List<OutgoingAttachment>) -> Unit,
    onError: (String) -> Unit,
): AttachmentPicker {
    val picked = rememberUpdatedState(onPicked)
    val failed = rememberUpdatedState(onError)
    val scope = rememberCoroutineScope()
    return remember {
        fun load(read: () -> List<Result<OutgoingAttachment>>) {
            scope.launch {
                val results = withContext(Dispatchers.IO) { read() }
                results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { failed.value(it.message ?: "Couldn't read a file.") }
                results.mapNotNull { it.getOrNull() }.takeIf { it.isNotEmpty() }?.let { picked.value(it) }
            }
        }

        fun read(files: List<File>) {
            if (files.isEmpty()) return
            load {
                // Everything picked sits in memory until it's sent, so a pick has a budget as a whole too.
                var left = MAX_PICK_BYTES
                files.map { file -> runCatching { readAttachment(file, left).also { left -= it.bytes.size } } }
            }
        }
        object : AttachmentPicker {
            fun pick(title: String, extensions: Set<String>?) {
                scope.launch {
                    fromDialog { openFiles(title, multiple = true, extensions) }
                        .onSuccess(::read)
                        .onFailure { failed.value("Couldn't open the file dialog.") }
                }
            }

            override fun pickPhotos() = pick("Attach photos", IMAGE_EXTENSIONS)

            override fun takePhoto() = failed.value("The desktop app can't use a camera yet.")

            override fun pickFiles() = pick("Attach files", null)

            override fun attachFiles(paths: List<String>) = read(paths.mapNotNull(::fileOf).filter { it.isFile })

            override fun pasteAttachments(): Boolean {
                val clipboard = systemClipboard() ?: return false
                // Copied folders aren't attached; with only those, the paste goes on as text (their paths).
                clipboard.read<List<*>>(DataFlavor.javaFileListFlavor)?.filterIsInstance<File>()?.filter { it.isFile }?.takeIf { it.isNotEmpty() }?.let { files ->
                    read(files)
                    return true
                }
                // A copied picture (a screenshot, an image copied from a page) has no file: it goes as a PNG would.
                clipboard.read<Image>(DataFlavor.imageFlavor)?.let { image ->
                    load { listOf(runCatching { imageAttachment("pasted.png", image.png(), MAX_PICK_BYTES) }) }
                    return true
                }
                val text = clipboard.read<String>(DataFlavor.stringFlavor) ?: return false
                if (text.length <= LARGE_PASTE) return false
                picked.value(listOf(pastedTextAttachment(text, newId())))
                return true
            }

            override fun clipboardText(): String? = systemClipboard()?.read<String>(DataFlavor.stringFlavor)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.fileDrop(onHover: (Boolean) -> Unit, onFiles: (List<String>) -> Unit): Modifier = composed {
    val latest = rememberUpdatedState(onFiles)
    val hover = rememberUpdatedState(onHover)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover.value(true)

            override fun onExited(event: DragAndDropEvent) = hover.value(false)

            override fun onEnded(event: DragAndDropEvent) = hover.value(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover.value(false)
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                if (files.isEmpty()) return false
                latest.value(files)
                return true
            }
        }
    }
    dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = target)
}

/** A dropped file's path: Compose hands over `file:` URIs. */
private fun fileOf(path: String): File? = runCatching { if (path.startsWith("file:")) File(URI(path)) else File(path) }.getOrNull()

private fun systemClipboard(): Clipboard? = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()

/** What the clipboard holds in [flavor]; null when it holds none, or another app has it locked just now. */
private inline fun <reified T> Clipboard.read(flavor: DataFlavor): T? =
    runCatching { if (isDataFlavorAvailable(flavor)) getData(flavor) as? T else null }.getOrNull()

private fun Image.png(): ByteArray {
    val buffered = this as? BufferedImage ?: BufferedImage(getWidth(null), getHeight(null), BufferedImage.TYPE_INT_ARGB).also {
        it.createGraphics().apply { drawImage(this@png, 0, 0, null) }.dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
}

private fun newId() = "att-${Random.nextLong().toULong().toString(16)}"

/** One picked file: photos at most [MAX_EDGE] px and JPEG; anything else as is. */
private fun readAttachment(file: File, budget: Long): OutgoingAttachment {
    val name = file.name
    val mime = Files.probeContentType(file.toPath()) ?: mimeTypeOf(name)
    val size = file.length()
    if (mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml") {
        if (size > MAX_PHOTO_BYTES) error("$name is larger than 50 MB.")
        return imageAttachment(name, file.readBytes(), budget)
    }
    if (size > OutgoingAttachment.MAX_BYTES) error("$name is larger than 25 MB.")
    if (size > budget) error("That's more than 50 MB of attachments at once.")
    val bytes = file.readBytes()
    val thumbnail = if (mime.startsWith("image/")) decodeScaled(bytes, THUMBNAIL_EDGE)?.jpeg(THUMBNAIL_QUALITY) else null
    return OutgoingAttachment(newId(), name, mime, bytes, thumbnail)
}

/** A picture, scaled to at most [MAX_EDGE] px and sent as JPEG, with its thumbnail. */
private fun imageAttachment(name: String, bytes: ByteArray, budget: Long): OutgoingAttachment {
    val scaled = decodeScaled(bytes, MAX_EDGE) ?: error("Couldn't read $name as an image.")
    val upload = scaled.jpeg(UPLOAD_QUALITY) ?: error("Couldn't read $name as an image.")
    if (upload.size > budget) error("That's more than 50 MB of attachments at once.")
    val thumbnail = decodeScaled(upload, THUMBNAIL_EDGE)?.jpeg(THUMBNAIL_QUALITY)
    return OutgoingAttachment(newId(), name.substringBeforeLast('.', name) + ".jpg", "image/jpeg", upload, thumbnail)
}

private const val MAX_EDGE = 2048
private const val UPLOAD_QUALITY = 85
private const val THUMBNAIL_EDGE = 320
private const val THUMBNAIL_QUALITY = 80
private const val MAX_PHOTO_BYTES = 50L * 1024 * 1024
private const val MAX_PICK_BYTES = 50L * 1024 * 1024

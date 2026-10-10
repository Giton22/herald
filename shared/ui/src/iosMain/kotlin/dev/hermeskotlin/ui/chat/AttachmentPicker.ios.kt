@file:OptIn(ExperimentalForeignApi::class)

package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.platform.toByteArray
import dev.hermeskotlin.ui.platform.hasCamera
import dev.hermeskotlin.ui.platform.jpeg
import dev.hermeskotlin.ui.platform.pickFiles as chooseFiles
import dev.hermeskotlin.ui.platform.pickPhotos as choosePhotos
import dev.hermeskotlin.ui.platform.takePhoto as shootPhoto
import dev.hermeskotlin.ui.platform.uiImage
import dev.hermeskotlin.ui.platform.uprightScaled
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIImage
import platform.UniformTypeIdentifiers.UTType
import kotlin.random.Random

@Composable
actual fun rememberAttachmentPicker(
    onPicked: (List<OutgoingAttachment>) -> Unit,
    onError: (String) -> Unit,
): AttachmentPicker {
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)

    fun deliver(results: List<Result<OutgoingAttachment>>) {
        results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { failed(it.message ?: "Couldn't read a file.") }
        results.mapNotNull { it.getOrNull() }.takeIf { it.isNotEmpty() }?.let(picked)
    }

    return remember {
        object : AttachmentPicker {
            override fun pickPhotos() {
                scope.launch {
                    val photos = choosePhotos(OutgoingAttachment.MAX_COUNT, MAX_PHOTO_BYTES)
                    if (photos.isEmpty()) return@launch
                    deliver(
                        withContext(Dispatchers.Default) {
                            readAll(photos.map { { budget -> photoAttachment(it.name, it.bytes ?: tooLarge(it.name ?: "A photo"), budget) } })
                        },
                    )
                }
            }

            override fun takePhoto() {
                if (!hasCamera()) {
                    failed("This device has no camera.")
                    return
                }
                scope.launch {
                    val photo = shootPhoto() ?: return@launch
                    deliver(withContext(Dispatchers.Default) { listOf(runCatching { photoAttachment(null, photo, MAX_PICK_BYTES) }) })
                }
            }

            override fun pickFiles() {
                scope.launch {
                    val urls = chooseFiles()
                    if (urls.isEmpty()) return@launch
                    try {
                        deliver(withContext(Dispatchers.IO) { readAll(urls.map { { budget -> fileAttachment(it, budget) } }) })
                    } finally {
                        // Cancelled before they were read: the picker's copies are still the app's to remove.
                        urls.forEach { NSFileManager.defaultManager.removeItemAtURL(it, error = null) }
                    }
                }
            }
        }
    }
}

@Composable
actual fun rememberImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? =
    remember(bytes, maxEdge) { decodeUpright(bytes, maxEdge)?.toBitmap() }

/**
 * Files shared from another app, read like files picked in Files (each one removed once read): the
 * attachments, and a sentence about the first that couldn't come along.
 */
internal fun readSharedFiles(urls: List<NSURL>): Pair<List<OutgoingAttachment>, String?> {
    val results = readAll(urls.map { { budget -> fileAttachment(it, budget) } })
    return results.mapNotNull { it.getOrNull() } to results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
}

/** Reads each item in turn within one pick's [MAX_PICK_BYTES], as the Android pickers do. */
private fun readAll(items: List<(budget: Long) -> OutgoingAttachment>): List<Result<OutgoingAttachment>> {
    var left = MAX_PICK_BYTES
    return items.map { read -> runCatching { read(left).also { left -= it.bytes.size } } }
}

/** A photo from the library or the camera: upright, at most [MAX_EDGE] px and JPEG, with a thumbnail. */
private fun photoAttachment(name: String?, original: ByteArray, budget: Long): OutgoingAttachment {
    val label = name ?: "photo"
    if (original.size > MAX_PHOTO_BYTES) tooLarge(label)
    val image = uiImage(original) ?: error("Couldn't read $label as an image.")
    return photoAttachment(name, image, budget)
}

private fun photoAttachment(name: String?, image: UIImage, budget: Long): OutgoingAttachment {
    val upright = image.uprightScaled(MAX_EDGE)
    val upload = upright.jpeg(UPLOAD_QUALITY)
    if (upload.size > budget) error("That's more than 50 MB of attachments at once.")
    val thumbnail = upright.uprightScaled(THUMBNAIL_EDGE).jpeg(THUMBNAIL_QUALITY)
    return OutgoingAttachment(newId(), (name ?: "photo-${Random.nextInt(100_000, 999_999)}").withExtension("jpg"), "image/jpeg", upload, thumbnail)
}

/** A file from Files: photos are handled as photos; anything else is sent as it is, within the limits. */
@OptIn(ExperimentalForeignApi::class)
private fun fileAttachment(url: NSURL, budget: Long): OutgoingAttachment = try {
    readFile(url, budget)
} finally {
    // The copy the picker made is the app's to remove.
    NSFileManager.defaultManager.removeItemAtURL(url, error = null)
}

@OptIn(ExperimentalForeignApi::class)
private fun readFile(url: NSURL, budget: Long): OutgoingAttachment {
    val name = url.lastPathComponent ?: "file"
    val mime = url.pathExtension?.let { UTType.typeWithFilenameExtension(it)?.preferredMIMEType } ?: "application/octet-stream"
    val size = url.path?.let { (NSFileManager.defaultManager.attributesOfItemAtPath(it, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue }
    if (mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml") {
        if (size != null && size > MAX_PHOTO_BYTES) error("$name is larger than 50 MB.")
        val data = NSData.dataWithContentsOfURL(url) ?: error("Couldn't read $name.")
        return photoAttachment(name, data.toByteArray(), budget)
    }
    if (size != null && size > OutgoingAttachment.MAX_BYTES) error("$name is larger than 25 MB.")
    if (size != null && size > budget) error("That's more than 50 MB of attachments at once.")
    val bytes = NSData.dataWithContentsOfURL(url)?.toByteArray() ?: error("Couldn't read $name.")
    if (bytes.size > OutgoingAttachment.MAX_BYTES || bytes.size > budget) error("$name is too large to attach.")
    val thumbnail = if (mime.startsWith("image/")) decodeUpright(bytes, THUMBNAIL_EDGE)?.jpeg(THUMBNAIL_QUALITY) else null
    return OutgoingAttachment(newId(), name, mime, bytes, thumbnail)
}

private fun tooLarge(label: String): Nothing = error("$label is larger than 50 MB.")

private fun newId() = "att-${Random.nextLong().toULong().toString(16)}"

private fun String.withExtension(ext: String): String = substringBeforeLast('.', this) + ".$ext"

/** Long edge of an uploaded photo: plenty for a vision model, a fraction of the original's tokens and bytes. */
private const val MAX_EDGE = 2048
private const val UPLOAD_QUALITY = 85
private const val THUMBNAIL_EDGE = 320
private const val THUMBNAIL_QUALITY = 80

/** An original photo read to be resized: any phone camera's photo fits, a mislabelled video doesn't. */
private const val MAX_PHOTO_BYTES = 50L * 1024 * 1024

/** Everything one pick adds; up to two full-size files. */
private const val MAX_PICK_BYTES = 50L * 1024 * 1024

@file:OptIn(ExperimentalForeignApi::class)

package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.hermeskotlin.core.platform.toNSData
import dev.hermeskotlin.ui.platform.present
import dev.hermeskotlin.ui.platform.release
import dev.hermeskotlin.ui.platform.retain
import dev.hermeskotlin.ui.platform.uiImage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.writeToURL
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import kotlin.coroutines.resume

@Composable
actual fun rememberMediaActions(): MediaActions = remember { IosMediaActions }

private object IosMediaActions : MediaActions {

    /** Pictures go to Photos; any other file to a folder the person picks in Files. */
    override suspend fun save(bytes: ByteArray, name: String): String {
        val image = mimeTypeOf(name).startsWith("image/") && uiImage(bytes) != null
        return if (image) saveToPhotos(bytes, name) else exportToFiles(bytes, name)
    }

    override fun share(bytes: ByteArray, name: String) {
        val file = tempFile(bytes, name) ?: return
        val sheet = UIActivityViewController(activityItems = listOf(file), applicationActivities = null)
        sheet.completionWithItemsHandler = { _, _, _, _ -> removeTempFile(file) }
        present(sheet)
    }

    private suspend fun saveToPhotos(bytes: ByteArray, name: String): String {
        val file = withContext(Dispatchers.IO) { tempFile(bytes, name) } ?: return "Couldn't save $name."
        val saved = try {
            suspendCancellableCoroutine { done ->
                // Adding only: the system asks once for "Add Photos Only", never for the whole library.
                PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                    { PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(file) },
                    completionHandler = { ok: Boolean, _: NSError? -> if (done.isActive) done.resume(ok) },
                )
            }
        } finally {
            // Photos has its own copy once the change is done; a cancelled save may still be reading it, which
            // a removed file just fails.
            removeTempFile(file)
        }
        return if (saved) "Saved to Photos" else "Couldn't save $name. Herald may not be allowed to add to Photos."
    }

    private suspend fun exportToFiles(bytes: ByteArray, name: String): String {
        val file = withContext(Dispatchers.IO) { tempFile(bytes, name) } ?: return "Couldn't save $name."
        val saved = try {
            suspendCancellableCoroutine { done ->
                val picker = UIDocumentPickerViewController(forExportingURLs = listOf(file), asCopy = true)
                val delegate = ExportDelegate(done)
                picker.delegate = delegate
                retain(picker, delegate)
                done.invokeOnCancellation {
                    release(picker)
                    picker.dismissViewControllerAnimated(true, completion = null)
                }
                if (!present(picker)) {
                    release(picker)
                    done.resume(null)
                }
            }
        } finally {
            removeTempFile(file)
        }
        return when (saved) {
            null -> "Couldn't save $name."
            false -> "Not saved"
            true -> "Saved to Files"
        }
    }
}

/** Whether the person picked a place (true) or closed the picker (false). */
private class ExportDelegate(private val done: CancellableContinuation<Boolean?>) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) = finish(controller, true)

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = finish(controller, false)

    private fun finish(controller: UIDocumentPickerViewController, saved: Boolean) {
        release(controller)
        if (done.isActive) done.resume(saved)
    }
}

/** [bytes] as a file named [name] in a fresh temporary folder, so the receiving app sees the original name. */
private fun tempFile(bytes: ByteArray, name: String): NSURL? {
    val dir = NSURL.fileURLWithPath(NSTemporaryDirectory()).URLByAppendingPathComponent("shared/${NSUUID().UUIDString}") ?: return null
    NSFileManager.defaultManager.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
    val file = dir.URLByAppendingPathComponent(name.replace('/', '_')) ?: return null
    return file.takeIf { bytes.toNSData().writeToURL(it, atomically = true) }
}

private fun removeTempFile(file: NSURL) {
    file.URLByDeletingLastPathComponent?.let { NSFileManager.defaultManager.removeItemAtURL(it, error = null) }
}

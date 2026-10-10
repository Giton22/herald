package dev.hermeskotlin.ui.platform

import dev.hermeskotlin.core.platform.toByteArray
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * A photo from the library: its encoded bytes as stored (JPEG, HEIC, PNG…) and the name the library suggests.
 * [bytes] is null when the original was too large to read.
 */
internal class PickedPhoto(val name: String?, val bytes: ByteArray?)

/**
 * The photo library picker, up to [limit] photos of at most [maxBytes] each; empty when the person cancels.
 * Photos that can't be read (still downloading from iCloud and offline, say) are left out.
 */
internal suspend fun pickPhotos(limit: Int, maxBytes: Long): List<PickedPhoto> {
    val results = suspendCancellableCoroutine { done ->
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = limit.toLong()
        }
        val picker = PHPickerViewController(configuration = config)
        val delegate = PhotoDelegate(done)
        picker.delegate = delegate
        // The picker holds its delegate weakly: keep it until the picker is done.
        done.invokeOnCancellation {
            release(picker)
            picker.dismissViewControllerAnimated(true, completion = null)
        }
        retain(picker, delegate)
        if (!present(picker)) {
            release(picker)
            done.resume(emptyList())
        }
    }
    // One at a time, each dropped before the next loads if it's over [maxBytes]: a pick of RAW originals
    // never sits in memory all at once.
    return results.mapNotNull { result ->
        val name = result.itemProvider.suggestedName
        when (val data = loadImage(result)) {
            null -> null
            else -> PickedPhoto(name, data.takeIf { it.length <= maxBytes.toULong() }?.toByteArray())
        }
    }
}

private suspend fun loadImage(result: PHPickerResult): NSData? = suspendCancellableCoroutine { done ->
    result.itemProvider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data: NSData?, _ ->
        done.resume(data)
    }
}

private class PhotoDelegate(private val done: CancellableContinuation<List<PHPickerResult>>) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        release(picker)
        if (done.isActive) done.resume(didFinishPicking.filterIsInstance<PHPickerResult>())
    }
}

/** Whether this device has a camera to take a photo with (the simulator has none). */
internal fun hasCamera(): Boolean =
    UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)

/** The camera, for one photo; null when the person cancels. */
internal suspend fun takePhoto(): UIImage? = suspendCancellableCoroutine { done ->
    val picker = UIImagePickerController().apply { sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera }
    val delegate = CameraDelegate(done)
    picker.delegate = delegate
    done.invokeOnCancellation {
        release(picker)
        picker.dismissViewControllerAnimated(true, completion = null)
    }
    retain(picker, delegate)
    if (!present(picker)) {
        release(picker)
        done.resume(null)
    }
}

private class CameraDelegate(private val done: CancellableContinuation<UIImage?>) :
    NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
        finish(picker, didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage)
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) = finish(picker, null)

    private fun finish(picker: UIImagePickerController, image: UIImage?) {
        picker.dismissViewControllerAnimated(true, completion = null)
        release(picker)
        if (done.isActive) done.resume(image)
    }
}

/** The Files picker, several files at once; it hands over copies the app may read. Empty when cancelled. */
internal suspend fun pickFiles(): List<NSURL> = suspendCancellableCoroutine { done ->
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
    picker.allowsMultipleSelection = true
    val delegate = FilesDelegate(done)
    picker.delegate = delegate
    done.invokeOnCancellation {
        release(picker)
        picker.dismissViewControllerAnimated(true, completion = null)
    }
    retain(picker, delegate)
    if (!present(picker)) {
        release(picker)
        done.resume(emptyList())
    }
}

private class FilesDelegate(private val done: CancellableContinuation<List<NSURL>>) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) =
        finish(controller, didPickDocumentsAtURLs.filterIsInstance<NSURL>())

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = finish(controller, emptyList())

    private fun finish(controller: UIDocumentPickerViewController, urls: List<NSURL>) {
        release(controller)
        if (done.isActive) done.resume(urls)
    }
}

// UIKit keeps delegates weakly; these hold each one for as long as its picker is up.
private val delegates = mutableMapOf<NSObject, NSObject>()

internal fun retain(controller: NSObject, delegate: NSObject) {
    delegates[controller] = delegate
}

internal fun release(controller: NSObject) {
    delegates.remove(controller)
}

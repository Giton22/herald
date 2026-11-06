package dev.hermeskotlin.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.chat.streamedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.random.Random
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

@Composable
actual fun rememberAttachmentPicker(
    onPicked: (List<OutgoingAttachment>) -> Unit,
    onError: (String) -> Unit,
): AttachmentPicker {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    // Survives the activity being recreated while the camera app is in front.
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }

    fun read(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val (read, error) = readAttachments(context, uris)
            error?.let(failed)
            read.takeIf { it.isNotEmpty() }?.let(picked)
        }
    }

    val photos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(OutgoingAttachment.MAX_COUNT),
    ) { read(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { read(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraFile?.let(::File) ?: return@rememberLauncherForActivityResult
        cameraFile = null
        if (!saved) {
            file.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { readAttachment(context, Uri.fromFile(file), MAX_PICK_BYTES) }.also { file.delete() }
            }
            result.onSuccess { picked(listOf(it)) }
            result.exceptionOrNull()?.let { failed(said(it, Res.string.android_attach_photo_failed)) }
        }
    }

    return remember {
        object : AttachmentPicker {
            override fun pickPhotos() =
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

            override fun takePhoto() {
                val dir = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }
                val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                cameraFile = file.absolutePath
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
                try {
                    camera.launch(uri)
                } catch (e: Exception) {
                    cameraFile = null
                    scope.launch { failed(getString(Res.string.android_attach_no_camera)) }
                }
            }

            override fun pickFiles() = files.launch(arrayOf("*/*"))
        }
    }
}

/**
 * Reads [uris] (picked here, or shared from another app) the way the composer's pickers do: photos
 * scaled down, everything within the per-file and per-pick limits. Returns what could be read and a
 * sentence for the first thing that couldn't.
 */
suspend fun readAttachments(context: Context, uris: List<Uri>): Pair<List<OutgoingAttachment>, String?> {
    val results = withContext(Dispatchers.IO) {
        // Everything picked sits in memory until it's sent, so a pick has a budget as a whole too.
        var left = MAX_PICK_BYTES
        uris.map { uri -> runCatching { readAttachment(context, uri, left).also { left -= it.bytes.size } } }
    }
    val error = results.firstNotNullOfOrNull { result ->
        when (val e = result.exceptionOrNull()) {
            null -> null
            // A share whose app didn't grant access; Android's message is a raw URI.
            is SecurityException -> getString(Res.string.android_attach_not_allowed)
            else -> said(e, Res.string.android_attach_read_failed)
        }
    }
    return results.mapNotNull { it.getOrNull() } to error
}

/** Why a file can't be attached, in the user's language when the words are the app's own. */
private class AttachmentProblem(val words: StringResource, vararg val args: Any) : Exception()

/** What [e] tells the user: the app's own reason, else the system's message, else [fallback]. */
private suspend fun said(e: Throwable, fallback: StringResource): String = when (e) {
    is AttachmentProblem -> getString(e.words, *e.args)
    else -> e.message ?: getString(fallback)
}

@Composable
actual fun rememberImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = remember(bytes, maxEdge) {
    decodeImageBitmap(bytes, maxEdge)
}

// A full-size photo is sampled down to what the view needs rather than decoded whole.
actual fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = decodeUpright(bytes, maxEdge)?.asImageBitmap()

actual fun Modifier.fileDrop(onHover: (Boolean) -> Unit, onFiles: (List<String>) -> Unit): Modifier = this

@Composable
actual fun rememberDecodedImage(bytes: ByteArray, maxEdge: Int): DecodedImage {
    if (!isTiff(bytes)) return DecodedImage(rememberImageBitmap(bytes, maxEdge), decoding = false)
    // Kyllene's own TIFF decoder is plain Kotlin: a big picture would hold up the UI thread.
    return produceState(DecodedImage(null, decoding = true), bytes, maxEdge) {
        // produceState keeps its last value across new keys: a different file starts at the spinner again.
        value = DecodedImage(null, decoding = true)
        value = DecodedImage(withContext(Dispatchers.Default) { decodeUpright(bytes, maxEdge)?.asImageBitmap() }, decoding = false)
    }.value
}

/**
 * Reads one picked item: photos are upright, at most [MAX_EDGE] px and JPEG; anything else is sent as is.
 * [budget] is what's left of the pick's [MAX_PICK_BYTES] for what this adds.
 */
private fun readAttachment(context: Context, uri: Uri, budget: Long): OutgoingAttachment {
    val resolver = context.contentResolver
    val name = resolver.displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    val mime = resolver.getType(uri) ?: guessMime(name)
    val id = "att-${Random.nextLong().toULong().toString(16)}"
    val size = resolver.size(uri)
    if (mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml") {
        // The original is read whole to decode it, so it has a ceiling of its own; only the resized JPEG is kept.
        if (size != null && size > MAX_PHOTO_BYTES) throw AttachmentProblem(Res.string.android_attach_photo_too_large, name)
        val original = resolver.openInputStream(uri)?.use {
            it.readAtMost(MAX_PHOTO_BYTES) ?: throw AttachmentProblem(Res.string.android_attach_photo_too_large, name)
        } ?: throw AttachmentProblem(Res.string.android_attach_unreadable, name)
        val upright = decodeUpright(original, MAX_EDGE) ?: throw AttachmentProblem(Res.string.android_attach_not_image, name)
        val upload = upright.jpeg(UPLOAD_QUALITY)
        if (upload.size > budget) throw AttachmentProblem(Res.string.android_attach_too_much)
        val thumbnail = upright.scaledTo(THUMBNAIL_EDGE).jpeg(THUMBNAIL_QUALITY)
        return OutgoingAttachment(id, name.withExtension("jpg"), "image/jpeg", upload, thumbnail)
    }
    if (size != null && size > OutgoingAttachment.MAX_BYTES) {
        throw AttachmentProblem(Res.string.android_attach_file_too_large, name, OutgoingAttachment.MAX_BYTES / 1_000_000)
    }
    // Too large for one socket message: read as it goes up, never held here (the pick's budget is for what is).
    if (size != null && size > OutgoingAttachment.MAX_INLINE_BYTES) {
        val source = streamedSource(size) { resolver.openInputStream(uri) ?: error("Couldn't read $name.") }
        return OutgoingAttachment(id, name, mime, ByteArray(0), source = source)
    }
    if (size != null && size > budget) throw AttachmentProblem(Res.string.android_attach_too_much)
    // The reported size can be missing or wrong, so the read itself stops at the limit.
    val stream = resolver.openInputStream(uri) ?: throw AttachmentProblem(Res.string.android_attach_unreadable, name)
    val bytes = stream.use { it.readAtMost(OutgoingAttachment.MAX_INLINE_BYTES.toLong()) } ?: run {
        // Past one socket message, with no size said up front: copied aside to learn it, then sent from there.
        val spooled = spool(context, uri, name)
        return OutgoingAttachment(id, name, mime, ByteArray(0), source = streamedSource(spooled.length()) { spooled.inputStream() })
    }
    if (bytes.size > budget) throw AttachmentProblem(Res.string.android_attach_too_much)
    val thumbnail = if (mime.startsWith("image/")) decodeUpright(bytes, THUMBNAIL_EDGE)?.jpeg(THUMBNAIL_QUALITY) else null
    return OutgoingAttachment(id, name, mime, bytes, thumbnail)
}

/**
 * Copies [uri] into the app's cache, up to [OutgoingAttachment.MAX_BYTES], for a file whose size its
 * provider doesn't say. Copies over a day old go first: one is only needed until its prompt is sent.
 */
private fun spool(context: Context, uri: Uri, name: String): File {
    val folder = File(context.cacheDir, SPOOL_FOLDER).apply { mkdirs() }
    val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    folder.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
    val file = File.createTempFile("att-", ".bin", folder)
    try {
        val stream = context.contentResolver.openInputStream(uri) ?: throw AttachmentProblem(Res.string.android_attach_unreadable, name)
        var copied = 0L
        stream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > OutgoingAttachment.MAX_BYTES) {
                        throw AttachmentProblem(Res.string.android_attach_file_too_large, name, OutgoingAttachment.MAX_BYTES / 1_000_000)
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        return file
    } catch (e: Throwable) {
        file.delete()
        throw e
    }
}

private const val SPOOL_FOLDER = "outgoing-attachments"

/** The whole stream, or null once it passes [limit] bytes. */
internal fun InputStream.readAtMost(limit: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > limit) return null
        out.write(buffer, 0, read)
    }
}

/** Decodes at roughly [maxEdge] (sampled, so a 50 MP photo never sits in memory whole) and applies the EXIF rotation. */
internal fun decodeUpright(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return decodeTiffUpright(bytes, maxEdge)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val orientation = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return bitmap.oriented(orientation).scaledTo(maxEdge)
}

/** A TIFF, which Android doesn't decode: read by [decodeTiff], already within [maxEdge]. Null for anything else. */
private fun decodeTiffUpright(bytes: ByteArray, maxEdge: Int): Bitmap? {
    if (!isTiff(bytes)) return null
    return try {
        val tiff = decodeTiff(bytes, maxEdge) ?: return null
        Bitmap.createBitmap(tiff.argb, tiff.width, tiff.height, Bitmap.Config.ARGB_8888).oriented(tiff.orientation)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // A file from an agent's reply: whatever goes wrong, out of memory included, the viewer says it can't show it.
        null
    }
}

/** This bitmap turned upright by an EXIF (or TIFF) orientation; the transposing ones are left as they are. */
private fun Bitmap.oriented(orientation: Int): Bitmap {
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
        }
    }
    return if (matrix.isIdentity) this else Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.scaledTo(maxEdge: Int): Bitmap {
    val edge = max(width, height)
    if (edge <= maxEdge) return this
    val scale = maxEdge.toFloat() / edge
    return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
}

internal fun Bitmap.jpeg(quality: Int): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

private fun ContentResolver.displayName(uri: Uri): String? = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
} else {
    uri.lastPathSegment
}

private fun ContentResolver.size(uri: Uri): Long? = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
} else {
    uri.path?.let { File(it).length() }
}

private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "pdf" -> "application/pdf"
    "txt", "md", "log" -> "text/plain"
    "json" -> "application/json"
    else -> "application/octet-stream"
}

private fun String.withExtension(ext: String): String = substringBeforeLast('.', this) + ".$ext"

private const val CAMERA_DIR = "camera"

/** Long edge of an uploaded photo: plenty for a vision model, a fraction of the original's tokens and bytes. */
private const val MAX_EDGE = 2048
private const val UPLOAD_QUALITY = 85
private const val THUMBNAIL_EDGE = 320
private const val THUMBNAIL_QUALITY = 80

/** An original photo read to be resized: any phone camera's photo fits, a mislabelled video doesn't. */
private const val MAX_PHOTO_BYTES = 50L * 1024 * 1024

/** Everything one pick adds; up to two full-size files. */
private const val MAX_PICK_BYTES = 50L * 1024 * 1024

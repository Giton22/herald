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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.hermeskotlin.core.chat.OutgoingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.random.Random

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
            result.onSuccess { picked(listOf(it)) }.onFailure { failed(it.message ?: "Couldn't read the photo.") }
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
                    failed("No camera app is available.")
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
            is SecurityException -> "The app that shared it didn't let Herald read the file."
            else -> e.message ?: "Couldn't read a file."
        }
    }
    return results.mapNotNull { it.getOrNull() } to error
}

@Composable
actual fun rememberImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = remember(bytes, maxEdge) {
    // A full-size photo is sampled down to what the view needs rather than decoded whole.
    decodeUpright(bytes, maxEdge)?.asImageBitmap()
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
        if (size != null && size > MAX_PHOTO_BYTES) error("$name is larger than 50 MB.")
        val original = resolver.openInputStream(uri)?.use { it.readAtMost(MAX_PHOTO_BYTES) ?: error("$name is larger than 50 MB.") }
            ?: error("Couldn't read $name.")
        val upright = decodeUpright(original, MAX_EDGE) ?: error("Couldn't read $name as an image.")
        val upload = upright.jpeg(UPLOAD_QUALITY)
        if (upload.size > budget) error("That's more than 50 MB of attachments at once.")
        val thumbnail = upright.scaledTo(THUMBNAIL_EDGE).jpeg(THUMBNAIL_QUALITY)
        return OutgoingAttachment(id, name.withExtension("jpg"), "image/jpeg", upload, thumbnail)
    }
    if (size != null && size > OutgoingAttachment.MAX_BYTES) error("$name is larger than 25 MB.")
    if (size != null && size > budget) error("That's more than 50 MB of attachments at once.")
    // The reported size can be missing or wrong, so the read itself stops at the limit.
    val limit = minOf(OutgoingAttachment.MAX_BYTES.toLong(), budget)
    val bytes = resolver.openInputStream(uri)?.use { it.readAtMost(limit) ?: error("$name is too large to attach.") }
        ?: error("Couldn't read $name.")
    val thumbnail = if (mime.startsWith("image/")) decodeUpright(bytes, THUMBNAIL_EDGE)?.jpeg(THUMBNAIL_QUALITY) else null
    return OutgoingAttachment(id, name, mime, bytes, thumbnail)
}

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
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val orientation = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
        }
    }
    val rotated = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    return rotated.scaledTo(maxEdge)
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

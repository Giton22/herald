package dev.hermeskotlin.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun rememberMediaActions(): MediaActions {
    val context = LocalContext.current
    return remember(context) { AndroidMediaActions(context) }
}

private class AndroidMediaActions(private val context: Context) : MediaActions {

    override suspend fun save(bytes: ByteArray, name: String): String = withContext(Dispatchers.IO) {
        // MediaStore renames a file whose extension doesn't match its type (todo.md as text/plain became
        // todo.md.txt), so take the type from Android's own table; unknown types keep the name as is.
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val image = mime.startsWith("image/")
        // Before Android 10 this needs a storage permission; sharing to Files covers those phones.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext "Saving needs Android 10. Use Share instead."
        val collection = if (image) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/Hermes"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return@withContext "Couldn't save $name."
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            "Saved to $folder"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            "Couldn't save $name."
        }
    }

    override fun share(bytes: ByteArray, name: String) {
        // A fresh folder per share keeps the original file name, which the receiving app shows.
        val dir = File(context.cacheDir, "$SHARE_DIR/${System.currentTimeMillis()}").apply { mkdirs() }
        val file = File(dir, name.replace('/', '_')).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeTypeOf(name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val SHARE_DIR = "shared"
    }
}

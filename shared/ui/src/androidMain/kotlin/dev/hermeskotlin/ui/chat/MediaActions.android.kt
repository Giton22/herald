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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

@Composable
actual fun rememberMediaActions(): MediaActions {
    val context = LocalContext.current
    return remember(context) { AndroidMediaActions(context) }
}

private class AndroidMediaActions(private val context: Context) : MediaActions {

    private val saved = SavedDownloadStore(context.applicationContext)

    override suspend fun hasSaved(sourceKey: String): Boolean = saved.find(sourceKey) != null

    override suspend fun openSaved(sourceKey: String): Boolean {
        val file = saved.find(sourceKey) ?: return false
        withContext(Dispatchers.Main) {
            context.startActivity(Intent(context, OpenSavedFileActivity::class.java)
                .setDataAndType(file.uri, file.mime)
                .putExtra(Intent.EXTRA_TITLE, file.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    override suspend fun shareSaved(sourceKey: String): Boolean {
        val file = saved.find(sourceKey) ?: return false
        withContext(Dispatchers.Main) {
            val send = Intent(Intent.ACTION_SEND).setType(file.mime)
                .putExtra(Intent.EXTRA_STREAM, file.uri)
                .apply { clipData = android.content.ClipData.newRawUri(file.name, file.uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    override suspend fun saveDownloaded(bytes: ByteArray, name: String, sourceKey: String): String =
        downloadSaveMutex.withLock {
            if (saved.find(sourceKey) != null) "Already saved on this phone"
            else saveFile(bytes, name, sourceKey)
        }

    override suspend fun save(bytes: ByteArray, name: String): String = saveFile(bytes, name, null)

    private suspend fun saveFile(bytes: ByteArray, name: String, sourceKey: String?): String = withContext(Dispatchers.IO) {
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
        val folder = (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/Herald"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        var savedUri: android.net.Uri? = null
        val uri = try {
            val inserted = resolver.insert(collection, values) ?: error("no entry")
            savedUri = inserted
            resolver.openOutputStream(inserted)?.use { it.write(bytes) } ?: error("no stream")
            check(resolver.update(inserted, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
            inserted
        } catch (e: Exception) {
            savedUri?.let { runCatching { resolver.delete(it, null, null) } }
            return@withContext "Couldn't save $name."
        }
        // Notify only after publishing. A blocked notification must never delete a successfully saved file.
        val openMime = mimeTypeOf(name).takeUnless { it == "application/octet-stream" } ?: mime
        if (sourceKey != null) saved.remember(sourceKey, uri, name, openMime)
        runCatching { notifySavedFile(context, uri, name, openMime) }
        "Saved to $folder"
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
        val downloadSaveMutex = Mutex()
        const val SHARE_DIR = "shared"
    }
}

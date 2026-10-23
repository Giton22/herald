package dev.hermeskotlin.ui.chat

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/** Maps an attachment's owner and source to the published file, never just its display name. */
internal class SavedDownloadStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("saved-downloads", Context.MODE_PRIVATE)

    suspend fun remember(sourceKey: String, uri: Uri, name: String, mime: String) = withContext(Dispatchers.IO) {
        val record = JSONObject().put("uri", uri.toString()).put("name", name).put("mime", mime)
        // Commit before reporting completion, so the next app launch can open the same copy.
        preferences.edit().putString(key(sourceKey), record.toString()).commit()
    }

    suspend fun find(sourceKey: String): SavedDownload? = withContext(Dispatchers.IO) {
        val key = key(sourceKey)
        val raw = preferences.getString(key, null) ?: return@withContext null
        try {
            val record = JSONObject(raw)
            val uri = Uri.parse(record.getString("uri"))
            check(uri.scheme == "content")
            // Opening a descriptor checks access without reading the document into memory.
            context.contentResolver.openFileDescriptor(uri, "r")?.use { }
                ?: error("Missing saved file")
            SavedDownload(uri, record.getString("name"), record.getString("mime"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            preferences.edit().remove(key).commit()
            null
        }
    }

    private fun key(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal data class SavedDownload(val uri: Uri, val name: String, val mime: String)

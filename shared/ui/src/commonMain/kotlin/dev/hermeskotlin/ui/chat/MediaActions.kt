package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable

/** Saving and sharing a picture or file the chat shows. */
interface MediaActions {
    /** Saves to the device's shared storage; returns a sentence saying where, or what went wrong. */
    suspend fun save(bytes: ByteArray, name: String): String

    /** Saves an attachment and remembers its source, so later taps can use the local copy. */
    suspend fun saveDownloaded(bytes: ByteArray, name: String, sourceKey: String): String = save(bytes, name)

    /** Whether the remembered file still exists and can be read. */
    suspend fun hasSaved(sourceKey: String): Boolean = false

    /** Returns false when the saved copy is missing; callers may then download it again. */
    suspend fun openSaved(sourceKey: String): Boolean = false

    /** Shares the original saved URI rather than downloading a second copy. */
    suspend fun shareSaved(sourceKey: String): Boolean = false

    /** Opens the system share sheet for the file. */
    fun share(bytes: ByteArray, name: String)
}

@Composable
expect fun rememberMediaActions(): MediaActions

/** A MIME type from a file name, for saving and sharing. */
internal fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    "avif" -> "image/avif"
    "ico" -> "image/x-icon"
    "tif", "tiff" -> "image/tiff"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    "txt", "log", "md" -> "text/plain"
    "csv" -> "text/csv"
    "html", "htm" -> "text/html"
    "json" -> "application/json"
    "zip" -> "application/zip"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    else -> "application/octet-stream"
}

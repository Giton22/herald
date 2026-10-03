package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable

/** Saving and sharing a picture or file the chat shows. */
interface MediaActions {
    /** Saves to the device's shared storage; returns a sentence saying where, or what went wrong. */
    suspend fun save(bytes: ByteArray, name: String): String

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

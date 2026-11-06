package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock

/** A file put on the gateway for an attachment: attach it by [path], then remove [folder]. */
data class StagedUpload(val path: String, val folder: String)

/** An upload that failed, or a gateway that has no upload ([unsupported]); [message] says which, for the user. */
class AttachmentUploadException(message: String, val unsupported: Boolean = false) : Exception(message)

@Serializable
private data class FilesListing(val path: String = "")

@Serializable
private data class FolderListing(val entries: List<FolderEntry> = emptyList())

@Serializable
private data class FolderEntry(
    val path: String = "",
    @SerialName("is_directory") val isDirectory: Boolean = false,
    val mtime: Double? = null,
)

@Serializable
private data class UploadReply(val path: String = "")

/**
 * Puts a file too large for one WebSocket message on the gateway over HTTP, for `file.attach` to take
 * by its path (`POST /api/files/upload-stream`, hermes_cli/web_routers/files.py,
 * on every gateway since v2026.7.1, up to 100 MiB). It goes in a folder of its own under the managed
 * files' default folder (`GET /api/files`: the gateway user's home, or the locked root), under its own
 * name, which is the name `file.attach` gives its copy; [discard] removes the folder once it's attached.
 *
 * One `file.attach` uses where it is (the chat's workspace holds the uploads folder) has to stay, as
 * the prompt refers to it. Those are removed after [RETENTION_SECONDS], as the gateway does its own
 * staged uploads: the first upload to a gateway in a run clears the folders older than that.
 */
class AttachmentUploads(private val client: HttpClient, private val clock: Clock = Clock.System) {

    private val homes = mutableMapOf<String, String>()
    private val pruned = mutableSetOf<String>()
    private val lock = Mutex()

    /** Uploads [attachment]; [onProgress] hears the bytes of it written so far, as they go. */
    suspend fun stage(
        gateway: GatewayUrl,
        attachment: OutgoingAttachment,
        onProgress: (sent: Long) -> Unit = {},
    ): StagedUpload {
        val home = home(gateway, attachment)
        val separator = if ('/' !in home && '\\' in home) "\\" else "/"
        val uploads = home.trimEnd('/', '\\') + separator + UPLOADS_FOLDER
        prune(gateway, uploads)
        val folder = uploads + separator + Random.nextLong().toULong().toString(16)
        val target = folder + separator + safeName(attachment.name)
        val content = MultipartUpload(listOf("path" to target, "overwrite" to "false"), attachment, onProgress)
        val result = try {
            apiCall {
                client.post(gateway.resolve("api/files/upload-stream")) {
                    // A large file over a slow link takes as long as it takes; only a stall gives up.
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = STALL_TIMEOUT_MS
                    }
                    setBody(content)
                }
            }.map { it.body<UploadReply>().path.ifEmpty { target } }
        } catch (e: CancellationException) {
            // The gateway may have kept all of it just before the reply was given up on.
            withContext(NonCancellable) { removeFolder(gateway, folder) }
            throw e
        }
        if (result is ApiResult.Success) return StagedUpload(result.value, folder)
        if (result is ApiResult.Failed && (result.status == 404 || result.status == 405)) {
            throw AttachmentUploadException(tooLargeHere(attachment), unsupported = true)
        }
        // A reply lost after the file was written leaves it there; nothing will attach it now.
        withContext(NonCancellable) { removeFolder(gateway, folder) }
        // The file itself failing (unreadable, or changed) says more than the request it broke off.
        content.failure?.let { throw it }
        throw when (result) {
            is ApiResult.Failed -> AttachmentUploadException("Couldn't upload ${attachment.name}: ${result.message}")
            else -> AttachmentUploadException("Couldn't upload ${attachment.name}. ${result.errorMessage}")
        }
    }

    /** Best effort: the folder [stage] made goes, with the file in it. */
    suspend fun discard(gateway: GatewayUrl, staged: StagedUpload) = removeFolder(gateway, staged.folder)

    private suspend fun removeFolder(gateway: GatewayUrl, folder: String) {
        apiCall {
            client.delete(gateway.resolve("api/files")) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("path", folder)
                    put("recursive", true)
                })
            }
        }
    }

    /** The managed files' default folder, asked once per gateway. */
    private suspend fun home(gateway: GatewayUrl, attachment: OutgoingAttachment): String {
        val key = gateway.toString()
        lock.withLock { homes[key] }?.let { return it }
        val home = when (val result = apiCall { client.get(gateway.resolve("api/files")) }) {
            is ApiResult.Success -> try {
                result.value.body<FilesListing>().path
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not JSON: a gateway from before the managed files, whose web app answers any path.
                throw AttachmentUploadException(tooLargeHere(attachment), unsupported = true)
            }.takeIf { it.isNotBlank() }
            // A gateway from before v2026.7.1 has no managed files, so no upload either.
            is ApiResult.Failed -> if (result.status == 404 || result.status == 405) {
                throw AttachmentUploadException(tooLargeHere(attachment), unsupported = true)
            } else {
                throw AttachmentUploadException("Couldn't reach the gateway's files: ${result.message}")
            }
            else -> throw AttachmentUploadException("Couldn't reach the gateway's files. ${result.errorMessage}")
        } ?: throw AttachmentUploadException("The gateway didn't say where files go.")
        lock.withLock { homes[key] = home }
        return home
    }

    /** Best effort, once per gateway: upload folders older than [RETENTION_SECONDS] go. */
    private suspend fun prune(gateway: GatewayUrl, uploads: String) {
        if (!lock.withLock { pruned.add(gateway.toString()) }) return
        try {
            val result = apiCall { client.get(gateway.resolve("api/files")) { parameter("path", uploads) } }
                .map { it.body<FolderListing>().entries }
            val entries = (result as? ApiResult.Success)?.value ?: return
            val cutoff = clock.now().epochSeconds - RETENTION_SECONDS
            for (entry in entries) {
                val mtime = entry.mtime ?: continue
                if (entry.isDirectory && entry.path.isNotEmpty() && mtime < cutoff) removeFolder(gateway, entry.path)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A leftover upload is only a file in the gateway's uploads folder.
        }
    }

    companion object {
        /** The folder uploads wait in until they're attached. */
        const val UPLOADS_FOLDER = ".kyllene-uploads"

        /** How long the upload may make no progress before it gives up. */
        const val STALL_TIMEOUT_MS = 60_000L

        /** How long an upload a chat uses in place is kept: a week, as the gateway keeps its own. */
        const val RETENTION_SECONDS = 7L * 24 * 60 * 60

        /** What a gateway without the upload says about a file past [OutgoingAttachment.MAX_INLINE_BYTES]. */
        fun tooLargeHere(attachment: OutgoingAttachment): String =
            "${attachment.name} is larger than ${OutgoingAttachment.MAX_INLINE_SIZE}, the most this gateway takes. " +
                "Update Hermes to send files up to ${OutgoingAttachment.MAX_SIZE}."

        /**
         * [name] as one path segment the gateway finds again: no folders, no `..`, nothing its path checks
         * turn down. `file.attach` expands `$VAR` and `%VAR%` and strips spaces off the path, so those
         * can't be in it or at its end, and a Windows device name (`CON`, `NUL.txt`) can't be a file.
         */
        internal fun safeName(name: String): String {
            val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
                .filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*$%" }
                .trim { it == ' ' || it == '.' }
            val safe = cleaned.ifEmpty { "attachment" }.take(MAX_NAME).trimEnd(' ', '.')
            return if (safe.substringBefore('.').uppercase() in WINDOWS_DEVICES) "_$safe" else safe
        }

        private const val MAX_NAME = 200

        private val WINDOWS_DEVICES =
            setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    }
}

/**
 * A multipart form of text [fields] and the [attachment] as its `file` part, written as it streams so
 * a large file is never held whole. Its length is known up front, so it goes with a Content-Length.
 * A file that can't be read, or changes as it's read, stops the body and is kept in [failure].
 */
private class MultipartUpload(
    fields: List<Pair<String, String>>,
    private val attachment: OutgoingAttachment,
    private val onProgress: (sent: Long) -> Unit,
) : OutgoingContent.WriteChannelContent() {

    var failure: AttachmentUploadException? = null
        private set

    private val boundary = "kyllene-" + Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)

    override val contentType: ContentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)

    private val head: ByteArray = buildString {
        for ((name, value) in fields) {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
            append(value).append("\r\n")
        }
        append("--").append(boundary).append("\r\n")
        // The name the gateway keeps comes from `path`; this one only has to be a valid header.
        val headerName = attachment.name.filter { it in ' '..'~' && it != '"' && it != '\\' }.ifEmpty { "attachment" }
        append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(headerName).append("\"\r\n")
        append("Content-Type: ").append(attachment.mimeType.ifBlank { "application/octet-stream" }).append("\r\n\r\n")
    }.encodeToByteArray()

    private val tail: ByteArray = "\r\n--$boundary--\r\n".encodeToByteArray()

    override val contentLength: Long = head.size + attachment.size + tail.size

    override suspend fun writeTo(channel: ByteWriteChannel) {
        try {
            writeForm(channel)
        } catch (e: AttachmentUploadException) {
            // The body is broken off with the cause, which fails the request; [stage] then says why from
            // [failure]. Not thrown on: the writer runs in the engine's own coroutine, which has no use for it.
            channel.cancel(e)
        }
    }

    private suspend fun writeForm(channel: ByteWriteChannel) {
        channel.writeFully(head)
        val source = attachment.source
        if (source == null) {
            channel.writeFully(attachment.bytes)
            onProgress(attachment.bytes.size.toLong())
        } else {
            var written = 0L
            fun changed() = fail("${attachment.name} changed while it was being sent. Attach it again.")
            val reader = readingFile { source.open() }
            reader.use {
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val read = readingFile { reader.read(buffer) }
                    if (read < 0) break
                    written += read
                    // A file that grew since it was picked would overrun the length already sent.
                    if (written > source.size) throw changed()
                    channel.writeFully(buffer, 0, read)
                    onProgress(written)
                }
            }
            if (written != source.size) throw changed()
        }
        channel.writeFully(tail)
    }

    /** [block] reads the file: a failure there (permission gone, file removed) is the file's, not the link's. */
    private suspend fun <T> readingFile(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AttachmentUploadException) {
        throw e
    } catch (_: Exception) {
        throw fail("Couldn't read ${attachment.name}. Attach it again.")
    }

    private fun fail(message: String) = AttachmentUploadException(message).also { failure = it }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}

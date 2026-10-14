package dev.hermeskotlin.core.media

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import io.ktor.client.request.parameter
import io.ktor.http.contentLength
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
private data class MediaResponse(@SerialName("data_url") val dataUrl: String = "")

@Serializable
private data class ReadTextResponse(
    val text: String = "",
    val byteSize: Long = 0,
    val truncated: Boolean = false,
    val binary: Boolean = false,
    val language: String? = null,
)

/**
 * Gateway-side images for a remote client (hermes_cli/web_routers/files.py `GET /api/media`), e.g. the
 * photos a stored prompt refers to with `@image:<path>`. Limited by the gateway to its media roots.
 */
class MediaApi(
    private val client: HttpClient,
    /** For pictures from the open web: no gateway cookies go along. */
    private val web: HttpClient = client,
    /** The most a linked file is fetched into memory for Save or Share ([download]). */
    private val maxDownloadBytes: Long = MAX_DOWNLOAD_BYTES,
) {

    /**
     * Any file on the gateway by absolute path (`GET /api/files/download`, what Desktop opens remote
     * `MEDIA:` files with), falling back to [image] for pictures when managed files are locked down.
     */
    suspend fun file(url: GatewayUrl, path: String): ApiResult<ByteArray> {
        val download = apiCall {
            client.get(url.resolve("api/files/download")) { parameter("path", path) }
        }.map { it.body<ByteArray>() }
        if (download is ApiResult.Success) return download
        val isImage = path.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        return if (isImage) image(url, path) else download
    }

    /**
     * The start of a gateway file as text, for a preview (`GET /api/fs/read-text`, Desktop's file
     * preview): up to the gateway's 512 KB cap, with whether it stopped short and whether the file
     * looked binary. [profile] picks the profile's backend when it runs in a sandbox.
     */
    suspend fun readText(url: GatewayUrl, path: String, profile: String? = null): ApiResult<FileText> = apiCall {
        client.get(url.resolve("api/fs/read-text")) {
            parameter("path", path)
            profile?.let { parameter("profile", it) }
        }
    }.map { response ->
        val body = response.body<ReadTextResponse>()
        FileText(body.text, body.byteSize, body.truncated, body.binary, body.language?.takeIf { it.isNotBlank() && it != "text" })
    }

    /**
     * A whole gateway file a reply linked to (`GET /api/fs/download`, which reads through the profile's
     * backend like the preview does), falling back to [file] on a gateway without it when no [profile]
     * is asked for.
     */
    suspend fun download(url: GatewayUrl, path: String, profile: String? = null): ApiResult<ByteArray> {
        // Streamed and counted: unlike the managed route, /api/fs/download has no size cap of its own.
        val result = client.prepareGet(url.resolve("api/fs/download")) {
            parameter("path", path)
            profile?.let { parameter("profile", it) }
        }.execute { response ->
            apiCall { response }.let { checked ->
                if (checked !is ApiResult.Success) return@execute checked.map { ByteArray(0) }
                if ((response.contentLength() ?: 0) > maxDownloadBytes) return@execute ApiResult.Failed(413, TOO_LARGE)
                val bytes = try {
                    response.bodyAsChannel().readRemaining(maxDownloadBytes + 1).readByteArray()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@execute ApiResult.Unavailable(e.message ?: "Download failed")
                }
                if (bytes.size > maxDownloadBytes) ApiResult.Failed(413, TOO_LARGE) else ApiResult.Success(bytes)
            }
        }
        // FastAPI's own "Not Found" means no such route; the route's own 404 says "File not found".
        val noRoute = result is ApiResult.Failed && (result.status == 405 || result.status == 404 && result.message in setOf("Not Found", "HTTP 404"))
        return when {
            !noRoute -> result
            // The managed route reads the host's disk, not the profile's backend: the same path in a
            // sandboxed profile could be a different file there, or none.
            profile != null -> ApiResult.Failed(501, NO_PROFILE_DOWNLOAD)
            else -> file(url, path)
        }
    }

    /** A reply's picture from the web (`![alt](https://...)`). */
    suspend fun remote(url: String): ApiResult<ByteArray> = apiCall { web.get(url) }.map { response ->
        // A reply can point anywhere; a huge "picture" mustn't fill the phone's memory.
        check((response.contentLength() ?: 0) <= MAX_REMOTE_BYTES) { "Picture is too large" }
        response.body<ByteArray>().also { check(it.size <= MAX_REMOTE_BYTES) { "Picture is too large" } }
    }

    companion object {
        private const val MAX_REMOTE_BYTES = 20L * 1024 * 1024

        const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024

        const val TOO_LARGE = "It's over 64 MB, too large to fetch to the phone from the chat."

        const val NO_PROFILE_DOWNLOAD = "This gateway can't hand over whole files from a profile yet. Update Hermes on it."
    }

    /** The image's bytes, decoded from the `data_url` the gateway answers with (media folders only). */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun image(url: GatewayUrl, path: String): ApiResult<ByteArray> = apiCall {
        client.get(url.resolve("api/media")) { parameter("path", path) }
    }.map { response ->
        val dataUrl = response.body<MediaResponse>().dataUrl
        Base64.decode(dataUrl.substringAfter("base64,"))
    }
}

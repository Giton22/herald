package dev.hermeskotlin.core.media

import dev.hermeskotlin.core.auth.WebSession
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ACCESS_BLOCKED
import dev.hermeskotlin.core.network.ApiResult
import io.ktor.http.URLBuilder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** What the chat plays in place of a file card. */
enum class MediaType { Audio, Video }

/**
 * Audio or video by [mimeType] when it says so (`audio/…`, `video/…`), else by [name]'s extension; null for
 * anything else. A web address's query and fragment don't count as part of the name.
 */
fun mediaTypeOf(name: String, mimeType: String? = null): MediaType? {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (mime.startsWith("audio/")) return MediaType.Audio
    if (mime.startsWith("video/")) return MediaType.Video
    return when (GatewayFileKind.of(name.substringBefore('?').substringBefore('#').substringAfterLast('/'))) {
        GatewayFileKind.Audio -> MediaType.Audio
        GatewayFileKind.Video -> MediaType.Video
        else -> null
    }
}

/**
 * The gateway's address for playing [path] (`GET /api/files/stream`): the managed file served inline, with
 * Range support, so a player can seek without fetching the whole file first. [profile] goes along as Desktop
 * sends it; the gateway reads the file from its own disk either way.
 */
fun mediaStreamUrl(gateway: GatewayUrl, path: String, profile: String? = null): String =
    URLBuilder(gateway.resolve("api/files/stream")).apply {
        parameters.append("path", path)
        profile?.takeIf { it.isNotBlank() }?.let { parameters.append("profile", it) }
    }.buildString()

/** How to play a gateway file, from what its stream route answered. */
sealed interface StreamPlan {
    /** The route serves it: play from [mediaStreamUrl]. */
    data object Stream : StreamPlan

    /**
     * Fetch the whole file instead (`/api/fs/download`, within the phone's cap): a gateway from before the
     * stream route, a type the route won't stream, or managed files locked to a folder this file isn't in.
     */
    data object Fetch : StreamPlan

    /** Neither route would help: the reason is [result]. */
    data class Refused(val result: ApiResult<*>) : StreamPlan
}

/**
 * The plan for the stream route's answer. A 404 is fetched too: an older gateway has no such route, and in a
 * sandboxed profile the file may only be in the profile's own backend; the download then says if it's gone.
 */
fun streamPlan(result: ApiResult<*>): StreamPlan = when (result) {
    is ApiResult.Success -> StreamPlan.Stream
    is ApiResult.Failed -> when {
        result.status == 403 && result.message.contains("sensitive", ignoreCase = true) -> StreamPlan.Refused(result)
        result.status == 403 && result.message == ACCESS_BLOCKED -> StreamPlan.Refused(result)
        result.status in FETCH_STATUSES -> StreamPlan.Fetch
        else -> StreamPlan.Refused(result)
    }
    else -> StreamPlan.Refused(result)
}

/** No route (404, 405 on a gateway that only knows GET for it), a type it won't stream (415), outside a locked root (403). */
private val FETCH_STATUSES = setOf(403, 404, 405, 415)

/**
 * The headers a player sends with each request for a gateway stream: the sign-in the app lends its own web
 * views ([dev.hermeskotlin.core.auth.WebCookieJar.sessionFor]), so the access token goes along but never the
 * refresh token, and the player can't rotate the app's session out from under it. Cookies become one
 * `Cookie` header, as a browser sends them.
 */
fun mediaRequestHeaders(session: WebSession): Map<String, String> = buildMap {
    putAll(session.headers)
    val cookies = session.cookies.map { it.substringBefore(';').trim() }.filter { '=' in it }
    if (cookies.isNotEmpty()) put("Cookie", cookies.joinToString("; "))
}

/** Where a player gets a file's bytes from, once worked out. */
sealed interface PlaySource {
    /**
     * The gateway's stream route ([mediaStreamUrl]), asked with [headers] ([mediaRequestHeaders]) and to no other
     * host. They carry the sign-in, so they never show in a log line.
     */
    class Gateway(val url: String, val headers: Map<String, String>) : PlaySource {
        override fun toString() = "Gateway($url)"
    }

    /** A web address, asked with no sign-in. */
    data class Web(val url: String) : PlaySource

    /** The whole file, at hand: a file the user sent, a `data:` URL, or one fetched in place of a stream. */
    class Bytes(val bytes: ByteArray) : PlaySource {
        override fun toString() = "Bytes(${bytes.size})"
    }

    /** Why it can't be played; the card's Save and Share may still get it. */
    data class Failed(val result: ApiResult<*>) : PlaySource
}

/**
 * Works out how to play what a chat shows: a gateway path, an `http(s)` address or a `data:` URL. A gateway
 * file is streamed when the gateway's stream route serves it ([streamPlan]), else fetched whole through the
 * chat's profile (up to [MediaApi.MAX_DOWNLOAD_BYTES]), else it says why not. [signIn] is the sign-in the app
 * lends a web view for an address ([dev.hermeskotlin.core.auth.WebCookieJar.sessionFor]), read after the
 * probe so a token the probe renewed is the one handed over.
 */
class MediaSources(private val media: MediaApi, private val signIn: suspend (String) -> WebSession) {

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun resolve(source: String, gateway: GatewayUrl?, profile: String?): PlaySource = when {
        source.startsWith("data:") -> {
            val bytes = if (source.substringBefore(',').endsWith(";base64", ignoreCase = true)) {
                runCatching { Base64.decode(source.substringAfter(',')) }.getOrNull()
            } else {
                null
            }
            bytes?.let { PlaySource.Bytes(it) } ?: PlaySource.Failed(ApiResult.Failed(0, "The clip in this message can't be read."))
        }
        source.startsWith("http://") || source.startsWith("https://") -> PlaySource.Web(source)
        gateway == null -> PlaySource.Failed(ApiResult.Unavailable("Not connected to a gateway."))
        else -> {
            val path = source.removePrefix("file://")
            when (val plan = streamPlan(media.probeStream(gateway, path, profile))) {
                StreamPlan.Stream -> {
                    val url = mediaStreamUrl(gateway, path, profile)
                    PlaySource.Gateway(url, mediaRequestHeaders(signIn(url)))
                }
                StreamPlan.Fetch -> when (val fetched = media.download(gateway, path, profile)) {
                    is ApiResult.Success -> PlaySource.Bytes(fetched.value)
                    else -> PlaySource.Failed(fetched)
                }
                is StreamPlan.Refused -> PlaySource.Failed(plan.result)
            }
        }
    }
}

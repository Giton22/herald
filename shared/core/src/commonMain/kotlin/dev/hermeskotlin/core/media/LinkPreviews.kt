package dev.hermeskotlin.core.media

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.contentLength
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.io.readByteArray

class LinkPreview(val url: String, val title: String, val description: String, val image: ByteArray?) {
    val domain: String get() = Url(url).host
}

internal class LinkMetadata(val title: String, val description: String, val image: String?)
internal expect fun linkMetadata(bytes: ByteArray, url: String, charset: String? = null): LinkMetadata?

/** The platform engine, refusing names that resolve to the user's own networks. */
internal expect fun publicWebEngine(): HttpClientEngine

/** A page or picture; [parsed] when [meta] already holds the page's metadata for exactly these [bytes]. */
private class WebContent(
    val url: String,
    val bytes: ByteArray,
    val charset: String?,
    val parsed: Boolean = false,
    val meta: LinkMetadata? = null,
)

/** What a site said before the deadline; null [content] is a lasting no. */
private class Answer(val content: WebContent?)

/** No answer that should be kept: a timeout, a dropped connection or a busy server. */
private class Unanswered : Exception()

/** A host that turned out to be on the user's own networks once resolved or connected: a lasting no. */
internal class NotPublicAddress(host: String) : IOException("$host isn't on the open internet")

private fun Throwable.notPublic(): Boolean = generateSequence(this) { it.cause }.any { it is NotPublicAddress }

/** The first three distinct webpage destinations in rendered Markdown order, excluding images and code. */
expect fun replyWebLinks(text: String): List<String>

private val ABSOLUTE_WEB_URL = Regex("""^https?://[^/?#\\\s:@]""", RegexOption.IGNORE_CASE)

internal fun webUrl(value: String): Url? = value.takeIf { ABSOLUTE_WEB_URL.containsMatchIn(it) }
    ?.let { runCatching { Url(it) }.getOrNull() }?.takeIf {
        it.protocol.name in setOf("https", "http") && it.host.isNotBlank() && it.user.isNullOrEmpty() && it.password.isNullOrEmpty() &&
            publicHost(it.host)
    }

private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa")

/**
 * Whether [host] may be on the open internet. A reply's links are fetched without a tap, so addresses on the
 * user's own networks (a router, the gateway, the tailnet) never are; names are checked again once resolved.
 */
internal fun publicHost(host: String): Boolean {
    val name = host.lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
    // Only global unicast (2000::/3): loopback, mapped IPv4, unique-local and link-local are all outside it.
    if (':' in name) return name.substringBefore(':').toIntOrNull(16) in 0x2000..0x3fff
    // Digits and dots are an address, read the lenient way (127.1, 10.1, 0177.0.0.1, fullwidth digits) by the
    // connection, so only a plain public dotted quad gets through.
    if (name.all { it.isDigit() || it in ".。．｡" }) {
        val octets = name.split('.')
        return octets.size == 4 && octets.all { it.length in 1..3 && it.all { c -> c in '0'..'9' } && (it == "0" || !it.startsWith('0')) && it.toInt() <= 255 } &&
            publicIpv4(octets.map { it.toInt() })
    }
    return '.' in name && LOCAL_SUFFIXES.none { name.endsWith(it) }
}

/** Whether a resolved address (4 or 16 bytes) is on the open internet. */
internal fun publicAddress(bytes: ByteArray): Boolean {
    val b = bytes.map { it.toInt() and 0xff }
    return when (b.size) {
        4 -> publicIpv4(b)
        // Global unicast, or a public IPv4 site reached through NAT64 (64:ff9b::/96) on an IPv6-only network.
        16 -> (b[0] and 0xe0) == 0x20 ||
            b.subList(0, 12) == listOf(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0) && publicIpv4(b.subList(12, 16))
        else -> false
    }
}

// This network, private, CGNAT (Tailscale), loopback, link-local, IETF, benchmarking, multicast and reserved.
private fun publicIpv4(b: List<Int>): Boolean = !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
    b[0] == 100 && b[1] in 64..127 || b[0] == 169 && b[1] == 254 || b[0] == 172 && b[1] in 16..31 ||
    b[0] == 192 && b[1] == 168 || b[0] == 192 && b[1] == 0 && b[2] == 0 || b[0] == 198 && b[1] in 18..19)

/** Webpage cards use only a credential-free client, with bounded downloads and parallelism. */
class LinkPreviews(private val web: HttpClient) {
    private val permits = Semaphore(2)
    private val cacheLock = Mutex()
    private val cache = linkedMapOf<String, LinkPreview?>()

    suspend fun load(url: String): LinkPreview? = permits.withPermit {
        cacheLock.withLock { if (cache.containsKey(url)) return@withPermit cache[url] }
        // A site's own answer is kept; a timeout, a dropped connection or a busy server is tried again next time.
        var lasting = true
        val card = try {
            fetch(url, PAGE_PREFIX_BYTES, PAGE_TIMEOUT, "text/html", "application/xhtml+xml", prefix = true)?.let { page ->
                val meta = (if (page.parsed) page.meta else withContext(Dispatchers.Default) { linkMetadata(page.bytes, page.url, page.charset) })
                    ?: return@let null
                val image = meta.image?.let { source ->
                    try {
                        fetch(source, MAX_IMAGE_BYTES, IMAGE_TIMEOUT, "image/")?.bytes
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lasting = e.notPublic()
                        null
                    }
                }
                LinkPreview(url, meta.title, meta.description, image)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A name that leads home stays refused; anything else may go next time.
            if (!e.notPublic()) return@withPermit null
            null
        }
        if (lasting) cacheLock.withLock {
            cache[url] = card
            // A few large thumbnails would otherwise hold tens of megabytes for as long as the app runs.
            while (cache.size > MAX_CACHED || cache.values.sumOf { it?.image?.size ?: 0 } > MAX_CACHED_IMAGE_BYTES) {
                cache.remove(cache.keys.first())
            }
        }
        card
    }

    /** The body, null for a lasting no; throws when there was no answer worth keeping. */
    private suspend fun fetch(source: String, limit: Int, timeout: Long, vararg types: String, prefix: Boolean = false): WebContent? {
        var url = webUrl(source) ?: return null
        var partial: WebContent? = null
        return try {
            val answer = withTimeoutOrNull(timeout) {
                repeat(4) {
                    var redirect: Url? = null
                    val result = web.prepareGet(url) {
                        header(HttpHeaders.Accept, types.joinToString(", ") { if (it.endsWith('/')) "${it}*" else it })
                    }.execute { response ->
                        if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                            redirect = response.headers[HttpHeaders.Location]?.let { location ->
                                webUrl(URLBuilder(url).apply {
                                    // takeFrom otherwise carries the old query even to a different host.
                                    if (location.isNotEmpty() && !location.startsWith('#')) parameters.clear()
                                    takeFrom(location)
                                }.buildString())
                            }
                            return@execute null
                        }
                        if (response.status.value == 429 || response.status.value >= 500) throw Unanswered()
                        val type = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').lowercase()
                        // The picture decoder can't draw SVG, so it isn't downloaded.
                        if (response.status.value !in 200..299 || type == "image/svg+xml" ||
                            types.none { if (it.endsWith('/')) type.startsWith(it) else type == it } ||
                            (!prefix && (response.contentLength() ?: 0) > limit)) return@execute null
                        val charset = runCatching { response.contentType()?.parameter("charset") }.getOrNull()
                        val channel = response.bodyAsChannel()
                        var bytes = channel.readRemaining(limit.toLong() + if (prefix) 0 else 1).readByteArray()
                        if (prefix && bytes.size == limit) {
                            val meta = withContext(Dispatchers.Default) { linkMetadata(bytes, url.toString(), charset) }
                            partial = WebContent(url.toString(), bytes, charset, parsed = true, meta = meta)
                            // Read on only for a picture declared later on; otherwise the prefix's metadata is the card's.
                            if (meta == null || meta.image != null) return@execute partial
                            bytes += channel.readRemaining((MAX_PAGE_BYTES - bytes.size).toLong()).readByteArray()
                        }
                        if (bytes.size > limit && !prefix) null else WebContent(url.toString(), bytes, charset)
                    }
                    if (result != null) return@withTimeoutOrNull Answer(result)
                    url = redirect ?: return@withTimeoutOrNull Answer(null)
                }
                Answer(null)
            }
            if (answer != null) answer.content else partial ?: throw Unanswered()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            partial ?: throw e
        }
    }

    private companion object {
        const val PAGE_PREFIX_BYTES = 256 * 1024
        const val MAX_PAGE_BYTES = 2 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val PAGE_TIMEOUT = 6_000L
        const val IMAGE_TIMEOUT = 4_000L
        const val MAX_CACHED = 32
        const val MAX_CACHED_IMAGE_BYTES = 8 * 1024 * 1024
    }
}

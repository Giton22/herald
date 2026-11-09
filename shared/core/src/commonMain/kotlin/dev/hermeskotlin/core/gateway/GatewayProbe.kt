package dev.hermeskotlin.core.gateway

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

sealed interface ProbeResult {
    /** A Hermes dashboard answered `/api/status`. */
    data class Reachable(val url: GatewayUrl, val status: GatewayStatus) : ProbeResult {
        /** A gated dashboard needs a password provider or browser sign-in for the app to get in. */
        val canSignIn: Boolean get() = !status.authRequired || status.supportsPasswordLogin || status.supportsNativeSignIn
    }

    /** Something answered, but not like a Hermes dashboard. */
    data class NotHermes(val url: GatewayUrl, val httpStatus: Int?) : ProbeResult

    /**
     * Cloudflare Access stopped the request before it reached Hermes: a redirect to its login page, or a
     * Cloudflare 403. [tokenSent] says whether a service token went with it, so whether it was turned down.
     */
    data class AccessBlocked(val url: GatewayUrl, val httpStatus: Int, val tokenSent: Boolean) : ProbeResult

    /** Nothing answered (DNS, refused, timeout, TLS...). */
    data class Unreachable(val url: GatewayUrl, val reason: String) : ProbeResult

    /**
     * The server asked for a client certificate and got none it accepts: nginx with `ssl_verify_client on`
     * answers 400 "No required SSL certificate was sent" (or 495/496); other servers fail the TLS handshake.
     * Attaching the certificate gets past it.
     */
    data class ClientCertRequired(val url: GatewayUrl, val reason: String) : ProbeResult
}

/** Public liveness probe used by "Test connection" before any sign-in. */
class GatewayProbe(private val client: HttpClient) {

    suspend fun probe(url: GatewayUrl): ProbeResult = try {
        val response = client.get(url.resolve("api/status")) {
            accept(ContentType.Application.Json)
            timeout { requestTimeoutMillis = PROBE_TIMEOUT_MS }
        }
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        val code = response.status.value
        val tokenSent = response.request.headers[CF_ACCESS_CLIENT_ID] != null
        val cloudflareRefused = isCloudflareRefusal(code, response.headers[HttpHeaders.Server], isJson)
        if (isAccessLoginRedirect(code, response.headers[HttpHeaders.Location]) || cloudflareRefused && tokenSent) {
            ProbeResult.AccessBlocked(url, code, tokenSent)
        } else if (!isJson && code in CERT_REPLY_CODES && isClientCertReply(code, response.bodyAsText().take(CERT_REPLY_SCAN))) {
            ProbeResult.ClientCertRequired(url, "HTTP $code")
        } else if (!response.status.isSuccess() || !isJson) {
            ProbeResult.NotHermes(url, response.status.value)
        } else {
            val status = response.body<GatewayStatus>()
            if (status.version == null) ProbeResult.NotHermes(url, response.status.value)
            else ProbeResult.Reachable(url, status)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: SerializationException) {
        ProbeResult.NotHermes(url, null)
    } catch (_: JsonConvertException) {
        ProbeResult.NotHermes(url, null)
    } catch (_: HttpRequestTimeoutException) {
        ProbeResult.Unreachable(url, "Timed out. Check the address, VPN or Tailscale connection.")
    } catch (e: Exception) {
        if (isClientCertDemand(e)) ProbeResult.ClientCertRequired(url, e.message ?: e::class.simpleName ?: "TLS handshake failed")
        else ProbeResult.Unreachable(url, e.message ?: e::class.simpleName ?: "Connection failed")
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 15_000L
    }
}

/**
 * Whether a non-JSON reply is a proxy turning the request down for want of a client certificate: nginx with
 * `ssl_verify_client on` finishes the handshake and answers 400 "No required SSL certificate was sent" (or
 * "The SSL certificate error" for one it rejects), and 495/496 where those codes aren't rewritten.
 */
internal fun isClientCertReply(code: Int, body: String): Boolean {
    if (code == 495 || code == 496) return true
    if (code != 400) return false
    val text = body.lowercase()
    return "no required ssl certificate was sent" in text || "the ssl certificate error" in text
}

/** The codes [isClientCertReply] can match, checked first so no other reply's body is read. */
private val CERT_REPLY_CODES = setOf(400, 495, 496)

/** How much of a reply [isClientCertReply] reads: nginx's error page names the certificate near its top. */
private const val CERT_REPLY_SCAN = 2_048

/**
 * Whether [error] looks like a TLS handshake that failed because the server demanded a client certificate and
 * got none it accepts: an SSL/TLS/handshake failure in the cause chain carrying a certificate alert *sent by
 * the server* ("Received fatal alert: …" on the JVM, `…_ALERT_…` from BoringSSL). When the app itself turns
 * down the server's certificate, the error names the certificate path instead, so that, a refused connection
 * or a timeout is not this.
 */
fun isClientCertDemand(error: Throwable): Boolean {
    val chain = generateSequence<Throwable>(error) { it.cause }.toList()
    if (chain.none { it::class.simpleName?.lowercase()?.contains(TLS_FAILURE) == true }) return false
    val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
    return CERT_ALERTS.any { "received fatal alert: $it" in text || "_alert_$it" in text }
}

private val TLS_FAILURE = Regex("ssl|tls|handshake")

/** TLS alerts a server sends when the client certificate is missing or not one it accepts. */
private val CERT_ALERTS = listOf("certificate_required", "bad_certificate", "certificate_unknown", "unknown_ca")

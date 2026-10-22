package dev.hermeskotlin.core.gateway

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
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
     * The TLS handshake failed because the server asked for a client certificate and got none it accepts
     * (a gateway behind nginx with `ssl_verify_client on`). Attaching the certificate gets past it.
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
 * Whether [error] looks like a TLS handshake that failed because the server demanded a client certificate
 * (nginx `ssl_verify_client`) and got none it accepts: an SSL/TLS/handshake failure in the cause chain whose
 * messages name certificates, CAs or the certificate request. Anything else (a refused connection, an
 * untrusted server certificate, a timeout) is not this.
 */
fun isClientCertDemand(error: Throwable): Boolean {
    val chain = generateSequence<Throwable>(error) { it.cause }.toList()
    if (chain.none { it::class.simpleName?.lowercase()?.contains(TLS_FAILURE) == true }) return false
    val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
    return CERT_DEMAND_HINTS.any { it in text }
}

private val TLS_FAILURE = Regex("ssl|tls|handshake")

private val CERT_DEMAND_HINTS = listOf(
    "certificate_required",
    "certificate required",
    "bad_certificate",
    "certificate_unknown",
    "unknown_ca",
    "unknown ca",
    "peer did not return a certificate",
    "no certificates",
    "empty certificate",
    "certificate request",
)

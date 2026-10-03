package dev.hermeskotlin.core.gateway

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

sealed interface ProbeResult {
    /** A Hermes dashboard answered `/api/status`. */
    data class Reachable(val url: GatewayUrl, val status: GatewayStatus) : ProbeResult {
        /** A non-loopback dashboard without a password provider can't be signed into from the app. */
        val canSignIn: Boolean get() = !status.authRequired || status.supportsPasswordLogin
    }

    /** Something answered, but not like a Hermes dashboard. */
    data class NotHermes(val url: GatewayUrl, val httpStatus: Int?) : ProbeResult

    /** Nothing answered (DNS, refused, timeout, TLS...). */
    data class Unreachable(val url: GatewayUrl, val reason: String) : ProbeResult
}

/** Public liveness probe used by "Test connection" before any sign-in. */
class GatewayProbe(private val client: HttpClient) {

    suspend fun probe(url: GatewayUrl): ProbeResult = try {
        val response = client.get(url.resolve("api/status")) {
            accept(ContentType.Application.Json)
            timeout { requestTimeoutMillis = PROBE_TIMEOUT_MS }
        }
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        if (!response.status.isSuccess() || !isJson) {
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
        ProbeResult.Unreachable(url, e.message ?: e::class.simpleName ?: "Connection failed")
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 15_000L
    }
}

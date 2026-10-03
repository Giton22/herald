package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthUser(
    @SerialName("user_id") val userId: String? = null,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val provider: String? = null,
) {
    val label: String get() = displayName ?: email ?: userId ?: "Signed in"
}

@Serializable
data class WsTicket(val ticket: String, @SerialName("ttl_seconds") val ttlSeconds: Int = 30)

@Serializable
private data class PasswordLoginBody(val provider: String, val username: String, val password: String)

/**
 * Dashboard auth (hermes_cli/dashboard_auth/routes.py). The session lives in cookies held by
 * [cookies]; the HTTP client must be built with that storage installed.
 */
class AuthApi(
    private val client: HttpClient,
    private val cookies: PersistentCookiesStorage,
) {

    /** `POST /auth/password-login` → session cookies. */
    suspend fun signIn(url: GatewayUrl, provider: String, username: String, password: String): ApiResult<Unit> =
        apiCall(isLogin = true) {
            client.post(url.resolve("auth/password-login")) {
                contentType(ContentType.Application.Json)
                setBody(PasswordLoginBody(provider, username, password))
            }
        }.map { }

    /** `GET /api/auth/me` — cheap check that the stored session is still valid. */
    suspend fun me(url: GatewayUrl): ApiResult<AuthUser> =
        apiCall { client.get(url.resolve("api/auth/me")) }.map { it.body<AuthUser>() }

    /** `POST /api/auth/ws-ticket` — single-use, ~30 s ticket for one `/api/ws` upgrade. */
    suspend fun mintWsTicket(url: GatewayUrl): ApiResult<WsTicket> =
        apiCall { client.post(url.resolve("api/auth/ws-ticket")) }.map { it.body<WsTicket>() }

    /** Best-effort server-side revoke, then forget the local cookies regardless. */
    suspend fun signOut(url: GatewayUrl) {
        try {
            client.post(url.resolve("auth/logout"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline sign-out still clears the local session.
        }
        cookies.clear(Url(url.value).host)
    }

    suspend fun hasStoredSession(url: GatewayUrl): Boolean = cookies.hasCookies(Url(url.value))
}

package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.GatewayUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
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

/** Outcome of an authenticated dashboard call. */
sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>

    /** 401 on login: wrong username/password. */
    data object InvalidCredentials : AuthResult<Nothing>

    /** 401 on an authenticated call: cookies missing/expired and refresh failed — sign in again. */
    data object SessionExpired : AuthResult<Nothing>

    /** 429: login rate limit. */
    data object RateLimited : AuthResult<Nothing>

    /** Network failure or 5xx: worth retrying. */
    data class Unavailable(val message: String) : AuthResult<Nothing>

    /** Anything else (4xx with a message). */
    data class Failed(val status: Int, val message: String) : AuthResult<Nothing>
}

@Serializable
private data class PasswordLoginBody(val provider: String, val username: String, val password: String)

@Serializable
private data class ErrorBody(val detail: String? = null, val error: String? = null, val message: String? = null)

/**
 * Dashboard auth (hermes_cli/dashboard_auth/routes.py). The session lives in cookies held by
 * [cookies]; the HTTP client must be built with that storage installed.
 */
class AuthApi(
    private val client: HttpClient,
    private val cookies: PersistentCookiesStorage,
) {

    /** `POST /auth/password-login` → session cookies. */
    suspend fun signIn(url: GatewayUrl, provider: String, username: String, password: String): AuthResult<Unit> =
        call(isLogin = true) {
            client.post(url.resolve("auth/password-login")) {
                contentType(ContentType.Application.Json)
                setBody(PasswordLoginBody(provider, username, password))
            }
        }.map { }

    /** `GET /api/auth/me` — cheap check that the stored session is still valid. */
    suspend fun me(url: GatewayUrl): AuthResult<AuthUser> =
        call { client.get(url.resolve("api/auth/me")) }.map { it.body<AuthUser>() }

    /** `POST /api/auth/ws-ticket` — single-use, ~30 s ticket for one `/api/ws` upgrade. */
    suspend fun mintWsTicket(url: GatewayUrl): AuthResult<WsTicket> =
        call { client.post(url.resolve("api/auth/ws-ticket")) }.map { it.body<WsTicket>() }

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

    private suspend fun call(isLogin: Boolean = false, block: suspend () -> HttpResponse): AuthResult<HttpResponse> =
        try {
            val response = block()
            val status = response.status
            when {
                status.isSuccess() -> AuthResult.Success(response)
                status == HttpStatusCode.Unauthorized ->
                    if (isLogin) AuthResult.InvalidCredentials else AuthResult.SessionExpired
                status == HttpStatusCode.TooManyRequests -> AuthResult.RateLimited
                status.value >= 500 -> AuthResult.Unavailable(response.errorMessage())
                else -> AuthResult.Failed(status.value, response.errorMessage())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Unavailable(e.message ?: e::class.simpleName ?: "Connection failed")
        }

    private suspend fun HttpResponse.errorMessage(): String {
        val body = runCatching { body<ErrorBody>() }.getOrNull()
        return body?.detail ?: body?.message ?: body?.error ?: "HTTP ${status.value}"
    }
}

private suspend fun <T, R> AuthResult<T>.map(transform: suspend (T) -> R): AuthResult<R> = when (this) {
    is AuthResult.Success -> try {
        AuthResult.Success(transform(value))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AuthResult.Failed(0, "Unexpected response: ${e.message}")
    }
    is AuthResult.InvalidCredentials -> this
    is AuthResult.SessionExpired -> this
    is AuthResult.RateLimited -> this
    is AuthResult.Unavailable -> this
    is AuthResult.Failed -> this
}

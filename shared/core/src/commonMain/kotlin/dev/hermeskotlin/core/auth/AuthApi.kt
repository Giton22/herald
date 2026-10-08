package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
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

@Serializable
private data class NativeTokenBody(val code: String, @SerialName("code_verifier") val codeVerifier: String)

/**
 * Dashboard auth (hermes_cli/dashboard_auth/routes.py). A password sign-in's session lives in cookies held by
 * [cookies]; a browser sign-in's in bearer tokens held by [tokens]. The HTTP client must be built with both installed.
 */
class AuthApi(
    private val client: HttpClient,
    private val cookies: PersistentCookiesStorage,
    /** Tests that never sign in through the browser leave this out. */
    private val tokens: NativeTokens = NativeTokens(InMemoryKeyValueStore()),
) {

    /** `GET /auth/native/authorize`, the page the browser sign-in starts at. A blank [provider] lets the gateway choose. */
    fun nativeAuthorizeUrl(url: GatewayUrl, provider: String?, codeChallenge: String, redirectUri: String, state: String): String =
        URLBuilder(url.resolve("auth/native/authorize")).apply {
            if (!provider.isNullOrBlank()) parameters.append("provider", provider)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("state", state)
        }.buildString()

    /**
     * `POST /auth/native/token`: the browser's one-time code and the PKCE verifier → bearer tokens, kept for [url].
     * An old password session's cookies go, so the app can't look signed in on them once the tokens end.
     */
    suspend fun redeemNativeCode(url: GatewayUrl, code: String, codeVerifier: String): ApiResult<Unit> =
        apiCall {
            client.post(url.resolve("auth/native/token")) {
                contentType(ContentType.Application.Json)
                setBody(NativeTokenBody(code, codeVerifier))
            }
        }.map { response ->
            tokens.set(url, response.body<NativeSession>())
            cookies.clear(Url(url.value).host)
        }

    /** `POST /auth/password-login` → session cookies. Old browser sign-in tokens would win over them, so they go. */
    suspend fun signIn(url: GatewayUrl, provider: String, username: String, password: String): ApiResult<Unit> =
        apiCall(isLogin = true) {
            client.post(url.resolve("auth/password-login")) {
                contentType(ContentType.Application.Json)
                setBody(PasswordLoginBody(provider, username, password))
            }
        }.map { tokens.set(url, null) }

    /** `GET /api/auth/me` — cheap check that the stored session is still valid. */
    suspend fun me(url: GatewayUrl): ApiResult<AuthUser> =
        apiCall { client.get(url.resolve("api/auth/me")) }.map { it.body<AuthUser>() }

    /** `POST /api/auth/ws-ticket` — single-use, ~30 s ticket for one `/api/ws` upgrade. */
    suspend fun mintWsTicket(url: GatewayUrl): ApiResult<WsTicket> =
        apiCall { client.post(url.resolve("api/auth/ws-ticket")) }.map { it.body<WsTicket>() }

    /**
     * Best-effort server-side revoke, then forget the local cookies and tokens regardless. The gateway has no revoke
     * for a browser sign-in's tokens; they are only forgotten here.
     */
    suspend fun signOut(url: GatewayUrl) {
        try {
            client.post(url.resolve("auth/logout"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline sign-out still clears the local session.
        }
        cookies.clear(Url(url.value).host)
        tokens.set(url, null)
    }

    suspend fun hasStoredSession(url: GatewayUrl): Boolean = tokens.get(url) != null || cookies.hasCookies(Url(url.value))
}

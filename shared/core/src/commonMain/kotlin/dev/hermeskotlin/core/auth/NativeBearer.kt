package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.GatewayUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class NativeRefreshBody(@SerialName("refresh_token") val refreshToken: String, val provider: String)

/**
 * Sends the browser sign-in's access token as `Authorization: Bearer` on every http(s) call to its gateway. The
 * gateway doesn't refresh a bearer session itself, so this does: shortly before the token expires, and once more
 * when a call comes back 401. A refresh the gateway turns down (`session_expired`) forgets the tokens, and the
 * call's 401 then signs the user out as usual. The `/api/ws` upgrade needs no token: it carries a ticket.
 */
fun nativeBearer(tokens: NativeTokens, clock: () -> Long = { getTimeMillis() }) = createClientPlugin("NativeBearer") {
    val refresher = NativeRefresher(client, tokens)
    on(Send) { request ->
        val protocol = request.url.protocol
        if (protocol != URLProtocol.HTTP && protocol != URLProtocol.HTTPS || NATIVE_PATH in request.url.encodedPath) {
            return@on proceed(request)
        }
        val url = request.url.build()
        var session = tokens.get(url) ?: return@on proceed(request)
        if (session.expiresAt > 0 && session.expiresAt * 1000 - clock() < REFRESH_AHEAD_MS) {
            session = refresher.refresh(url, session) ?: tokens.get(url) ?: return@on proceed(request)
        }
        request.headers[HttpHeaders.Authorization] = "Bearer ${session.accessToken}"
        val call = proceed(request)
        if (call.response.status != HttpStatusCode.Unauthorized) return@on call
        val fresh = refresher.refresh(url, session) ?: return@on call
        val retry = HttpRequestBuilder().takeFrom(request)
        retry.headers[HttpHeaders.Authorization] = "Bearer ${fresh.accessToken}"
        proceed(retry)
    }
}

/** One refresh at a time per app: a burst of calls after expiry must not replay a rotated refresh token. */
private class NativeRefresher(private val client: HttpClient, private val tokens: NativeTokens) {

    private val mutex = Mutex()

    /** New tokens, or null when the gateway turned the refresh down (the tokens are then forgotten) or can't be reached. */
    suspend fun refresh(url: Url, used: NativeSession): NativeSession? = mutex.withLock {
        val current = tokens.get(url) ?: return@withLock null
        // Another call refreshed while this one waited.
        if (current.accessToken != used.accessToken) return@withLock current
        try {
            val response = client.post(GatewayUrl.parse(current.baseUrl).resolve("auth/native/refresh")) {
                contentType(ContentType.Application.Json)
                setBody(NativeRefreshBody(current.refreshToken, current.provider.orEmpty()))
            }
            when {
                response.status.isSuccess() ->
                    response.body<NativeSession>().copy(baseUrl = current.baseUrl).also { tokens.set(url, it) }
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.BadRequest -> {
                    tokens.set(url, null)
                    null
                }
                // 503 (identity provider down) and the like: keep the tokens and try again later.
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

private const val NATIVE_PATH = "/auth/native/"
private const val REFRESH_AHEAD_MS = 60_000L

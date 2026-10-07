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
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class NativeRefreshBody(@SerialName("refresh_token") val refreshToken: String, val provider: String)

/**
 * Sends the browser sign-in's access token as `Authorization: Bearer` on the `/api/` calls to its gateway. The
 * gateway doesn't refresh a bearer session itself, so this does when a call comes back 401, then retries it once.
 * The `/auth/` routes are public and get no token, and the `/api/ws` upgrade carries a ticket instead.
 *
 * A refresh the gateway turns down (`session_expired`) forgets the tokens, and the call's 401 then signs the user
 * out as usual. A refresh it can't do (its identity provider is down, 503) keeps them, and the call fails as
 * unreachable, so the app retries instead of signing out.
 */
fun nativeBearer(tokens: NativeTokens, clock: () -> Long = { getTimeMillis() }) = createClientPlugin("NativeBearer") {
    val refresher = NativeRefresher(client, tokens, clock)
    on(Send) { request ->
        val protocol = request.url.protocol
        if (protocol != URLProtocol.HTTP && protocol != URLProtocol.HTTPS || API_PATH !in request.url.encodedPath) {
            return@on proceed(request)
        }
        val session = tokens.forRequest(request.url.build()) ?: return@on proceed(request)
        request.headers[HttpHeaders.Authorization] = "Bearer ${session.accessToken}"
        val call = proceed(request)
        if (call.response.status != HttpStatusCode.Unauthorized) return@on call
        when (val refresh = refresher.refresh(session)) {
            is Refresh.Done -> {
                val retry = HttpRequestBuilder().takeFrom(request)
                retry.headers[HttpHeaders.Authorization] = "Bearer ${refresh.session.accessToken}"
                proceed(retry)
            }
            Refresh.Rejected -> call
            Refresh.Unreachable -> throw IOException("The gateway couldn't renew the sign-in: its sign-in provider is unreachable.")
        }
    }
}

private sealed interface Refresh {
    data class Done(val session: NativeSession) : Refresh
    /** The gateway turned the refresh token down; the tokens are forgotten. */
    data object Rejected : Refresh
    /** No answer, or a 503: try again later. */
    data object Unreachable : Refresh
}

/**
 * One refresh at a time per gateway: a burst of 401s must not replay a rotated refresh token. A refresh that
 * couldn't get through is the answer for every call waiting on it, and for [RETRY_AFTER_MS] after.
 */
private class NativeRefresher(private val client: HttpClient, private val tokens: NativeTokens, private val clock: () -> Long) {

    private val locks = mutableMapOf<String, Mutex>()
    private val locksGuard = Mutex()

    /** When the refresh for an access token last couldn't get through, by that token. */
    private val failedAt = mutableMapOf<String, Long>()

    suspend fun refresh(used: NativeSession): Refresh = lockFor(used.baseUrl).withLock {
        val gateway = GatewayUrl.parse(used.baseUrl)
        val current = tokens.get(gateway) ?: return@withLock Refresh.Rejected
        // Another call refreshed while this one waited.
        if (current.accessToken != used.accessToken) return@withLock Refresh.Done(current)
        failedAt[current.accessToken]?.let { if (clock() - it < RETRY_AFTER_MS) return@withLock Refresh.Unreachable }
        val outcome = try {
            val response = client.post(gateway.resolve("auth/native/refresh")) {
                contentType(ContentType.Application.Json)
                setBody(NativeRefreshBody(current.refreshToken, current.provider.orEmpty()))
            }
            when {
                response.status.isSuccess() -> Refresh.Done(response.body<NativeSession>().also { tokens.set(gateway, it) })
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.BadRequest -> {
                    tokens.set(gateway, null)
                    Refresh.Rejected
                }
                else -> Refresh.Unreachable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Refresh.Unreachable
        }
        if (outcome == Refresh.Unreachable) failedAt[current.accessToken] = clock() else failedAt.remove(current.accessToken)
        outcome
    }

    private suspend fun lockFor(baseUrl: String) = locksGuard.withLock { locks.getOrPut(baseUrl) { Mutex() } }
}

private const val API_PATH = "/api/"
private const val RETRY_AFTER_MS = 30_000L

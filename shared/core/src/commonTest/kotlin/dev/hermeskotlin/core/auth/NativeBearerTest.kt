package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeBearerTest {

    private val url = GatewayUrl.parse("http://100.64.0.1:9119")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)
    private val tokens = NativeTokens(store)

    /** Unix seconds; the access tokens expire relative to this. */
    private val now = getTimeMillis() / 1000

    private fun refreshed(at: String, rt: String) =
        """{"access_token":"$at","refresh_token":"$rt","token_type":"Bearer","expires_at":${now + 900},"provider":"self-hosted","user_id":"u1"}"""

    private fun clientWith(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        AuthApi(createHttpClient(MockEngine(handler), cookies, bearer = tokens), cookies, tokens)

    private suspend fun signedIn(expiresAt: Long = now + 900) = tokens.set(
        Url(url.value),
        NativeSession("AT1", "RT1", expiresAt, provider = "self-hosted", baseUrl = url.value),
    )

    private val HttpRequestData.bodyText get() = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    @Test
    fun sendsTheAccessTokenAndCountsAsSignedIn() = runTest {
        signedIn()
        var auth: String? = null
        val api = clientWith { request ->
            auth = request.headers[HttpHeaders.Authorization]
            respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
        }
        assertTrue(api.hasStoredSession(url))
        assertIs<ApiResult.Success<WsTicket>>(api.mintWsTicket(url))
        assertEquals("Bearer AT1", auth)
    }

    @Test
    fun otherHostsGetNoToken() = runTest {
        signedIn()
        var auth: String? = "unset"
        val api = clientWith { request ->
            auth = request.headers[HttpHeaders.Authorization]
            respond("""{"user_id":"u"}""", HttpStatusCode.OK, json)
        }
        api.me(GatewayUrl.parse("http://100.64.0.2:9119"))
        assertNull(auth)
        // Same host, another port: another gateway.
        api.me(GatewayUrl.parse("http://100.64.0.1:9120"))
        assertNull(auth)
    }

    @Test
    fun a401RefreshesOnceAndRetries() = runTest {
        signedIn()
        val seen = mutableListOf<String>()
        val api = clientWith { request ->
            when (request.url.encodedPath) {
                "/auth/native/refresh" -> {
                    seen += "refresh:${request.headers[HttpHeaders.Authorization]}"
                    assertTrue("\"refresh_token\":\"RT1\"" in request.bodyText && "\"provider\":\"self-hosted\"" in request.bodyText)
                    respond(refreshed("AT2", "RT2"), HttpStatusCode.OK, json)
                }
                else -> {
                    val auth = request.headers[HttpHeaders.Authorization]
                    seen += "me:$auth"
                    if (auth == "Bearer AT1") respond("""{"error":"session_expired"}""", HttpStatusCode.Unauthorized, json)
                    else respond("""{"user_id":"u1"}""", HttpStatusCode.OK, json)
                }
            }
        }

        assertIs<ApiResult.Success<AuthUser>>(api.me(url))
        assertEquals(listOf("me:Bearer AT1", "refresh:null", "me:Bearer AT2"), seen)
        val stored = tokens.get(Url(url.value))!!
        assertEquals("AT2", stored.accessToken)
        assertEquals("RT2", stored.refreshToken)
        assertEquals(url.value, stored.baseUrl)
    }

    @Test
    fun refreshesBeforeTheTokenExpires() = runTest {
        signedIn(expiresAt = now + 10)
        val seen = mutableListOf<String>()
        val client = createHttpClient(
            MockEngine { request ->
                seen += request.url.encodedPath + " " + request.headers[HttpHeaders.Authorization]
                if (request.url.encodedPath == "/auth/native/refresh") respond(refreshed("AT2", "RT2"), HttpStatusCode.OK, json)
                else respond("""{"user_id":"u1"}""", HttpStatusCode.OK, json)
            },
            cookies,
        ).config { install(nativeBearer(tokens) { now * 1000 }) }
        val api = AuthApi(client, cookies, tokens)

        assertIs<ApiResult.Success<AuthUser>>(api.me(url))
        assertEquals(listOf("/auth/native/refresh null", "/api/auth/me Bearer AT2"), seen)
    }

    @Test
    fun aRefusedRefreshForgetsTheTokens() = runTest {
        signedIn()
        val api = clientWith { request ->
            if (request.url.encodedPath == "/auth/native/refresh") respond("""{"error":"session_expired"}""", HttpStatusCode.Unauthorized, json)
            else respond("""{"error":"session_expired"}""", HttpStatusCode.Unauthorized, json)
        }
        assertEquals(ApiResult.SessionExpired, api.me(url))
        assertNull(tokens.get(Url(url.value)))
        assertFalse(api.hasStoredSession(url))
    }

    @Test
    fun anUnreachableIdentityProviderKeepsTheTokens() = runTest {
        signedIn()
        val api = clientWith { request ->
            if (request.url.encodedPath == "/auth/native/refresh") respond("""{"detail":"Auth provider unreachable"}""", HttpStatusCode.ServiceUnavailable, json)
            else respond("""{"error":"session_expired"}""", HttpStatusCode.Unauthorized, json)
        }
        assertEquals(ApiResult.SessionExpired, api.me(url))
        assertEquals("AT1", tokens.get(Url(url.value))?.accessToken)
    }

    @Test
    fun parallelCallsShareOneRefresh() = runTest {
        signedIn()
        var refreshes = 0
        val api = clientWith { request ->
            when {
                request.url.encodedPath == "/auth/native/refresh" -> {
                    refreshes++
                    respond(refreshed("AT2", "RT2"), HttpStatusCode.OK, json)
                }
                request.headers[HttpHeaders.Authorization] == "Bearer AT1" ->
                    respond("""{"error":"session_expired"}""", HttpStatusCode.Unauthorized, json)
                else -> respond("""{"user_id":"u1"}""", HttpStatusCode.OK, json)
            }
        }
        val results = List(5) { async { api.me(url) } }.awaitAll()
        assertTrue(results.all { it is ApiResult.Success })
        assertEquals(1, refreshes)
    }

    @Test
    fun signOutForgetsTheTokens() = runTest {
        signedIn()
        val api = clientWith { respond("", HttpStatusCode.Found) }
        api.signOut(url)
        assertNull(tokens.get(Url(url.value)))
    }

    @Test
    fun aPasswordSignInReplacesBrowserTokens() = runTest {
        signedIn()
        val api = clientWith { respond("""{"ok":true,"next":"/"}""", HttpStatusCode.OK, json) }
        assertIs<ApiResult.Success<Unit>>(api.signIn(url, "basic", "me", "pw"))
        assertNull(tokens.get(Url(url.value)))
    }

    @Test
    fun authorizeUrlKeepsThePathPrefix() {
        val api = AuthApi(createHttpClient(MockEngine { error("no calls") }), cookies, tokens)
        val built = Url(
            api.nativeAuthorizeUrl(GatewayUrl.parse("https://h.example.com/hermes"), "", "CC", "http://127.0.0.1:5/callback", "ST"),
        )
        assertEquals("/hermes/auth/native/authorize", built.encodedPath)
        assertEquals("CC", built.parameters["code_challenge"])
        assertEquals("ST", built.parameters["state"])
        assertNull(built.parameters["provider"])
    }
}

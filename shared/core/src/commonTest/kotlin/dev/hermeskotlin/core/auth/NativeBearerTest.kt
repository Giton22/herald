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
    private var clock = 0L

    private fun refreshed(at: String, rt: String) =
        """{"access_token":"$at","refresh_token":"$rt","token_type":"Bearer","expires_at":1900000000,"provider":"self-hosted","user_id":"u1"}"""

    private fun clientWith(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): AuthApi {
        val client = createHttpClient(MockEngine(handler), cookies).config { install(nativeBearer(tokens) { clock }) }
        return AuthApi(client, cookies, tokens)
    }

    private suspend fun signedIn(gateway: GatewayUrl = url, access: String = "AT1") =
        tokens.set(gateway, NativeSession(access, "RT1", 1900000000, provider = "self-hosted"))

    private val HttpRequestData.bodyText get() = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private val expired: suspend MockRequestHandleScope.() -> HttpResponseData =
        { respond("""{"error":"session_expired"}""", HttpStatusCode.Unauthorized, json) }

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
    fun otherGatewaysGetNoToken() = runTest {
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
    fun gatewaysBehindOneProxyKeepTheirOwnTokens() = runTest {
        val a = GatewayUrl.parse("https://proxy.example.com/a")
        val b = GatewayUrl.parse("https://proxy.example.com/b")
        signedIn(a, "AT-A")
        signedIn(b, "AT-B")
        val seen = mutableMapOf<String, String?>()
        val api = clientWith { request ->
            seen[request.url.encodedPath] = request.headers[HttpHeaders.Authorization]
            respond("""{"user_id":"u"}""", HttpStatusCode.OK, json)
        }
        api.me(a)
        api.me(b)
        api.me(GatewayUrl.parse("https://proxy.example.com/ab"))
        assertEquals("Bearer AT-A", seen["/a/api/auth/me"])
        assertEquals("Bearer AT-B", seen["/b/api/auth/me"])
        assertNull(seen["/ab/api/auth/me"])

        api.signOut(a)
        assertNull(tokens.get(a))
        assertEquals("AT-B", tokens.get(b)?.accessToken)
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
                    if (auth == "Bearer AT1") expired() else respond("""{"user_id":"u1"}""", HttpStatusCode.OK, json)
                }
            }
        }

        assertIs<ApiResult.Success<AuthUser>>(api.me(url))
        assertEquals(listOf("me:Bearer AT1", "refresh:null", "me:Bearer AT2"), seen)
        val stored = tokens.get(url)!!
        assertEquals("AT2", stored.accessToken)
        assertEquals("RT2", stored.refreshToken)
        assertEquals(url.value, stored.baseUrl)
    }

    @Test
    fun aRefusedRefreshForgetsTheTokens() = runTest {
        signedIn()
        val api = clientWith { expired() }
        assertEquals(ApiResult.SessionExpired, api.me(url))
        assertNull(tokens.get(url))
        assertFalse(api.hasStoredSession(url))
    }

    @Test
    fun anUnreachableIdentityProviderIsNotASignOut() = runTest {
        signedIn()
        var refreshes = 0
        val api = clientWith { request ->
            if (request.url.encodedPath == "/auth/native/refresh") {
                refreshes++
                respond("""{"detail":"Auth provider unreachable"}""", HttpStatusCode.ServiceUnavailable, json)
            } else expired()
        }
        assertIs<ApiResult.Unavailable>(api.me(url))
        assertEquals("AT1", tokens.get(url)?.accessToken)

        // Calls right after don't each ask again...
        assertIs<ApiResult.Unavailable>(api.me(url))
        assertEquals(1, refreshes)
        // ...but a while later they do.
        clock += 31_000
        assertIs<ApiResult.Unavailable>(api.me(url))
        assertEquals(2, refreshes)
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
                request.headers[HttpHeaders.Authorization] == "Bearer AT1" -> expired()
                else -> respond("""{"user_id":"u1"}""", HttpStatusCode.OK, json)
            }
        }
        val results = List(5) { async { api.me(url) } }.awaitAll()
        assertTrue(results.all { it is ApiResult.Success })
        assertEquals(1, refreshes)
    }

    @Test
    fun aWrongPasswordIsNotRetriedWithATokenRefresh() = runTest {
        signedIn()
        val seen = mutableListOf<String>()
        val api = clientWith { request ->
            seen += request.url.encodedPath + " " + request.headers[HttpHeaders.Authorization]
            respond("""{"detail":"Invalid credentials"}""", HttpStatusCode.Unauthorized, json)
        }
        assertEquals(ApiResult.InvalidCredentials, api.signIn(url, "basic", "me", "wrong"))
        assertEquals(listOf("/auth/password-login null"), seen)
    }

    @Test
    fun aPasswordSignInReplacesBrowserTokens() = runTest {
        signedIn()
        val api = clientWith { respond("""{"ok":true,"next":"/"}""", HttpStatusCode.OK, json) }
        assertIs<ApiResult.Success<Unit>>(api.signIn(url, "basic", "me", "pw"))
        assertNull(tokens.get(url))
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

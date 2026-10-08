package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.AccessToken
import dev.hermeskotlin.core.gateway.AccessTokens
import dev.hermeskotlin.core.gateway.CF_ACCESS_CLIENT_ID
import dev.hermeskotlin.core.gateway.CF_ACCESS_CLIENT_SECRET
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebCookieJarTest {

    private val gateway = "https://hermes.example.ts.net"
    private val page = "$gateway/example-board"
    private var now = 1_000_000L
    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store) { now }
    private val access = AccessTokens(store)
    private val tokens = NativeTokens(store)

    private fun jar(handler: MockRequestHandler = { error("no calls") }): WebCookieJar {
        val client = createHttpClient(MockEngine(handler), cookies = cookies, access = access, bearer = tokens)
        return WebCookieJar(cookies, access, tokens, AuthApi(client, cookies, tokens)) { now }
    }

    @Test
    fun aCookieSessionLendsTheAccessTokenButNeverTheRefreshToken() = runTest {
        cookies.addCookie(Url(gateway), Cookie("__Host-hermes_session_at", "at", maxAge = 600, path = "/", secure = true, httpOnly = true))
        cookies.addCookie(Url(gateway), Cookie("__Host-hermes_session_rt", "rt", maxAge = 86400, path = "/", secure = true, httpOnly = true))

        val session = jar().sessionFor(page)

        assertEquals(listOf("__Host-hermes_session_at=at; Path=/; Secure; HttpOnly"), session.cookies)
        assertEquals(now + 600_000, session.expiresAt)
    }

    @Test
    fun aBrowserSignInLendsItsBearerTokenAsTheSessionCookie() = runTest {
        // The gateway checks the access-token cookie with the same providers as a bearer token.
        tokens.set(GatewayUrl.parse(gateway), NativeSession("AT1", "RT1", expiresAt = 1_900_000_000, provider = "oidc"))

        val session = jar().sessionFor(page)

        assertEquals(
            listOf("hermes_session_at=AT1; Path=/; Secure; HttpOnly", "hermes_session_provider=oidc; Path=/; Secure; HttpOnly"),
            session.cookies,
        )
        assertEquals(1_900_000_000_000, session.expiresAt)
    }

    @Test
    fun theAccessServiceTokenGoesOverHttpsOnly() = runTest {
        access.set("hermes.example.ts.net", AccessToken("id", "secret"))

        assertEquals(mapOf(CF_ACCESS_CLIENT_ID to "id", CF_ACCESS_CLIENT_SECRET to "secret"), jar().sessionFor(page).headers)
        assertTrue(jar().sessionFor("http://hermes.example.ts.net/example-board").headers.isEmpty())
        assertTrue(jar().sessionFor("https://other.ts.net/example-board").headers.isEmpty())
    }

    @Test
    fun renewingLetsTheGatewayRotateTheAppsSessionThenLendsTheNewToken() = runTest {
        cookies.addCookie(Url(gateway), Cookie("__Host-hermes_session_at", "old", maxAge = 600, path = "/", secure = true, httpOnly = true))
        cookies.addCookie(Url(gateway), Cookie("__Host-hermes_session_rt", "rt1", maxAge = 86400, path = "/", secure = true, httpOnly = true))
        var sent: String? = null
        val jar = jar { request ->
            sent = request.headers[HttpHeaders.Cookie]
            // The gateway's cookie gate: no access token but a refresh token, so it rotates both.
            respond(
                """{"user_id":"u1"}""", HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.SetCookie to listOf(
                        "__Host-hermes_session_at=new; Max-Age=600; Path=/; Secure; HttpOnly",
                        "__Host-hermes_session_rt=rt2; Max-Age=86400; Path=/; Secure; HttpOnly",
                    ),
                ),
            )
        }
        now += 601_000

        jar.renew(gateway, page)

        assertEquals("__Host-hermes_session_rt=rt1", sent)
        val session = jar.sessionFor(page)
        assertEquals(listOf("__Host-hermes_session_at=new; Path=/; Secure; HttpOnly"), session.cookies)
        assertEquals(now + 600_000, session.expiresAt)
    }

    @Test
    fun renewalsAtTheSameMomentRotateTheRefreshTokenOnce() = runTest {
        // The page's timer, its trip to the sign-in and Try again can all fire at once; a second renewal
        // carrying the same refresh token would replay it.
        cookies.addCookie(Url(gateway), Cookie("__Host-hermes_session_at", "old", maxAge = 600, path = "/", secure = true, httpOnly = true))
        cookies.addCookie(Url(gateway), Cookie("__Host-hermes_session_rt", "rt1", maxAge = 86400, path = "/", secure = true, httpOnly = true))
        var calls = 0
        val jar = jar {
            calls++
            respond(
                """{"user_id":"u1"}""", HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.SetCookie to listOf("__Host-hermes_session_at=new; Max-Age=600; Path=/; Secure; HttpOnly"),
                ),
            )
        }

        jar.renew(gateway, page)
        assertEquals(0, calls, "a token that hasn't lapsed isn't renewed")

        now += 601_000
        coroutineScope { repeat(3) { launch { jar.renew(gateway, page) } } }
        assertEquals(1, calls)
    }
}

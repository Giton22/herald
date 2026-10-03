package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        AuthApi(createHttpClient(MockEngine(handler), cookies), cookies)

    @Test
    fun signInStoresSessionCookiesAndSendsThemOnTicketMint() = runTest {
        var ticketCookie: String? = null
        val api = api { request ->
            when (request.url.encodedPath) {
                "/auth/password-login" -> {
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    assertTrue("\"provider\":\"basic\"" in body && "\"username\":\"me\"" in body)
                    respond(
                        """{"ok":true,"next":"/"}""", HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.SetCookie to listOf(
                                "__Host-hermes_session_at=AT1; Max-Age=900; Path=/; Secure; HttpOnly; SameSite=lax",
                                "__Host-hermes_session_rt=RT1; Max-Age=86400; Path=/; Secure; HttpOnly; SameSite=lax",
                            ),
                            HttpHeaders.ContentType to listOf("application/json"),
                        ),
                    )
                }
                "/api/auth/ws-ticket" -> {
                    ticketCookie = request.headers[HttpHeaders.Cookie]
                    respond("""{"ticket":"T-123","ttl_seconds":30}""", HttpStatusCode.OK, json)
                }
                else -> error("unexpected ${request.url}")
            }
        }

        assertIs<AuthResult.Success<Unit>>(api.signIn(url, "basic", "me", "pw"))
        assertTrue(api.hasStoredSession(url))

        val ticket = assertIs<AuthResult.Success<WsTicket>>(api.mintWsTicket(url))
        assertEquals("T-123", ticket.value.ticket)
        assertTrue(ticketCookie!!.contains("__Host-hermes_session_at=AT1"))
        assertTrue(ticketCookie!!.contains("__Host-hermes_session_rt=RT1"))

        // Persisted: a fresh jar over the same store still has the session.
        assertTrue(PersistentCookiesStorage(store).hasCookies(io.ktor.http.Url(url.value)))
    }

    @Test
    fun badPasswordIsInvalidCredentials() = runTest {
        val api = api { respond("""{"detail":"Invalid credentials"}""", HttpStatusCode.Unauthorized, json) }
        assertEquals(AuthResult.InvalidCredentials, api.signIn(url, "basic", "me", "wrong"))
    }

    @Test
    fun rateLimited() = runTest {
        val api = api { respond("""{"detail":"Too many login attempts."}""", HttpStatusCode.TooManyRequests, json) }
        assertEquals(AuthResult.RateLimited, api.signIn(url, "basic", "me", "pw"))
    }

    @Test
    fun expiredSessionOnTicketMint() = runTest {
        val api = api { respond("""{"reason":"session_expired"}""", HttpStatusCode.Unauthorized, json) }
        assertEquals(AuthResult.SessionExpired, api.mintWsTicket(url))
    }

    @Test
    fun serverErrorIsUnavailableWithDetail() = runTest {
        val api = api { respond("""{"detail":"Provider unreachable: boom"}""", HttpStatusCode.ServiceUnavailable, json) }
        val result = assertIs<AuthResult.Unavailable>(api.signIn(url, "basic", "me", "pw"))
        assertEquals("Provider unreachable: boom", result.message)
    }

    @Test
    fun meParsesUser() = runTest {
        val api = api { respond("""{"user_id":"u1","display_name":"Ada","provider":"basic","extra":1}""", HttpStatusCode.OK, json) }
        val me = assertIs<AuthResult.Success<AuthUser>>(api.me(url))
        assertEquals("Ada", me.value.label)
    }

    @Test
    fun signOutClearsCookiesEvenWhenOffline() = runTest {
        cookies.addCookie(io.ktor.http.Url(url.value), io.ktor.http.Cookie("hermes_session_rt", "RT", maxAge = 100, path = "/"))
        val api = api { throw IllegalStateException("offline") }
        api.signOut(url)
        assertFalse(api.hasStoredSession(url))
        assertNull(cookies.get(io.ktor.http.Url(url.value)).firstOrNull())
    }
}

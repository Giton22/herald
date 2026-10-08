package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.http.renderCookieHeader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistentCookiesStorageTest {

    private var now = 1_000_000L
    private val storage = PersistentCookiesStorage(InMemoryKeyValueStore()) { now }
    private val https = Url("https://hermes.example.ts.net/api/status")

    @Test
    fun hostOnlyCookieDoesNotLeakToOtherHostsOrPlainHttp() = runTest {
        storage.addCookie(https, Cookie("a", "1", path = "/", secure = true))
        assertEquals(1, storage.get(https).size)
        assertTrue(storage.get(Url("https://other.ts.net/")).isEmpty())
        assertTrue(storage.get(Url("https://sub.hermes.example.ts.net/")).isEmpty())
        assertTrue(storage.get(Url("http://hermes.example.ts.net/")).isEmpty())
    }

    @Test
    fun maxAgeExpires() = runTest {
        storage.addCookie(https, Cookie("a", "1", maxAge = 10, path = "/"))
        assertEquals(1, storage.get(https).size)
        now += 11_000
        assertTrue(storage.get(https).isEmpty())
    }

    @Test
    fun replacingCookieKeepsOneCopyAndZeroMaxAgeDeletes() = runTest {
        storage.addCookie(https, Cookie("a", "1", maxAge = 100, path = "/"))
        storage.addCookie(https, Cookie("a", "2", maxAge = 100, path = "/"))
        assertEquals(listOf("2"), storage.get(https).map { it.value })
        storage.addCookie(https, Cookie("a", "", maxAge = 0, path = "/"))
        assertTrue(storage.get(https).isEmpty())
    }

    @Test
    fun base64urlTokenValueSurvivesStoreAndSendWithoutUriEncoding() = runTest {
        // Hermes signs session tokens as padded base64url, so the cookie value ends in '='.
        // Ktor's default URI encoding would send it as %3D and the server's HMAC check would fail.
        val token = "eyJzdWIiOiJoYW1vdWRpIn0.c2lnbmF0dXJl=="
        val setCookie = parseServerSetCookieHeader(
            "hermes_session_at=$token; HttpOnly; Max-Age=43200; Path=/; SameSite=lax",
        )
        assertEquals(CookieEncoding.RAW, setCookie.encoding)

        storage.addCookie(https, setCookie)
        val sent = storage.get(https).single()

        assertEquals(token, sent.value)
        assertEquals(CookieEncoding.RAW, sent.encoding)
        val header = renderCookieHeader(sent)
        assertEquals("hermes_session_at=$token", header)
        assertFalse(header.contains("%3D"), "value must not be URI-encoded on the wire")
    }

    @Test
    fun quotedTokenFromTheGatewayGoesBackAsTheGatewayReadsIt() = runTest {
        // The gateway's cookie library quotes a value containing '=', so the real header carries quotes (#67).
        // They come off on the way in; the padding has to survive on the way out.
        val token = "eyJzdWIiOiJoYW1vdWRpIn0.c2lnbmF0dXJl=="
        storage.addCookie(https, parseServerSetCookieHeader("hermes_session_at=\"$token\"; HttpOnly; Max-Age=43200; Path=/; SameSite=lax"))

        assertEquals("hermes_session_at=$token", renderCookieHeader(storage.get(https).single()))
    }

    @Test
    fun aQuotedValueWithASpaceStillGoesOut() = runTest {
        // The gateway's cookie library keeps a space inside the quotes. Raw, it goes back as the value itself
        // (which the gateway's parser reads as sent), not as `my%20sso`, and sending doesn't throw.
        storage.addCookie(https, parseServerSetCookieHeader("hermes_session_provider=\"my sso\"; HttpOnly; Path=/"))

        assertEquals("hermes_session_provider=my sso", renderCookieHeader(storage.get(https).single()))
    }

    @Test
    fun cookieStoredBeforeEncodingFieldExistedIsReSentRaw() = runTest {
        // A v1 StoredCookie JSON with no `encoding` field (as persisted before the fix).
        val legacy = InMemoryKeyValueStore().apply {
            put(
                "cookies.v1",
                """[{"name":"hermes_session_at","value":"tok==","domain":"hermes.example.ts.net",""" +
                    """"hostOnly":true,"path":"/","secure":true,"httpOnly":true,"expiresAt":null}]""",
            )
        }
        val sent = PersistentCookiesStorage(legacy) { now }.get(https).single()
        assertEquals(CookieEncoding.RAW, sent.encoding)
        assertEquals("hermes_session_at=tok==", renderCookieHeader(sent))
    }

    @Test
    fun pathScoping() = runTest {
        storage.addCookie(https, Cookie("p", "1", maxAge = 100, path = "/prefix"))
        assertTrue(storage.get(Url("https://hermes.example.ts.net/prefix/api/ws")).isNotEmpty())
        assertTrue(storage.get(Url("https://hermes.example.ts.net/prefixed")).isEmpty())
        assertTrue(storage.get(Url("https://hermes.example.ts.net/")).isEmpty())
    }

    @Test
    fun webCookiesCarryWhatTheWebViewJarNeeds() = runTest {
        storage.addCookie(https, Cookie("hermes_session_at", "abc=", path = "/", secure = true, httpOnly = true))
        storage.addCookie(https, Cookie("scoped", "v", domain = "hermes.example.ts.net", path = "/", secure = true))
        val values = storage.webCookies("https://hermes.example.ts.net/example-board").values

        // A host-only cookie goes in without a Domain; one with an explicit scope keeps it.
        assertTrue("hermes_session_at=abc=; Path=/; Secure; HttpOnly" in values)
        assertTrue("scoped=v; Path=/; Domain=hermes.example.ts.net; Secure" in values)
    }

    @Test
    fun webCookiesSkipWhatDoesNotMatch() = runTest {
        storage.addCookie(https, Cookie("a", "1", path = "/", secure = true))
        // A secure session cookie never goes to a plain-http view, and an unreadable URL gets nothing.
        assertTrue(storage.webCookies("http://hermes.example.ts.net/").values.isEmpty())
        assertTrue(storage.webCookies("not a url").values.isEmpty())
    }

    @Test
    fun webCookiesCarryCookiesForOtherPathsOnTheHost() = runTest {
        // The page is opened at its own path but calls the gateway's API under /api, so an /api-scoped
        // session cookie must reach the view's jar too, still scoped to /api.
        storage.addCookie(https, Cookie("api_only", "v", path = "/api", secure = true))
        val values = storage.webCookies("https://hermes.example.ts.net/example-board").values
        assertEquals(listOf("api_only=v; Path=/api; Secure"), values)
    }

    @Test
    fun webCookiesKeepTheRefreshTokenAndSayWhenTheAccessTokenLapses() = runTest {
        storage.addCookie(https, Cookie("__Host-hermes_session_at", "at", maxAge = 3600, path = "/", secure = true, httpOnly = true))
        storage.addCookie(https, Cookie("__Host-hermes_session_rt", "rt", maxAge = 86400, path = "/", secure = true, httpOnly = true))
        storage.addCookie(https, Cookie("__Host-hermes_session_provider", "basic", path = "/", secure = true, httpOnly = true))

        val lent = storage.webCookies("https://hermes.example.ts.net/example-board")

        // Only the app renews: a page holding the rotating refresh token could replay it behind the app's back.
        assertEquals(listOf("__Host-hermes_session_at", "__Host-hermes_session_provider"), lent.values.map { it.substringBefore('=') })
        assertEquals(now + 3_600_000, lent.accessExpiresAt)
    }
}

package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

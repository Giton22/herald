package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.AccessToken
import dev.hermeskotlin.core.gateway.AccessTokens
import dev.hermeskotlin.core.gateway.CF_ACCESS_CLIENT_ID
import dev.hermeskotlin.core.gateway.CF_ACCESS_CLIENT_SECRET
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
    fun setCookieValuesCarryWhatTheWebViewJarNeeds() = runTest {
        storage.addCookie(https, Cookie("hermes_session_at", "abc=", path = "/", secure = true, httpOnly = true))
        storage.addCookie(https, Cookie("scoped", "v", domain = "hermes.example.ts.net", path = "/", secure = true))
        val values = storage.setCookieValues("https://hermes.example.ts.net/example-board")

        // A host-only cookie goes in without a Domain; one with an explicit scope keeps it.
        assertTrue("hermes_session_at=abc=; Path=/; Secure; HttpOnly" in values)
        assertTrue("scoped=v; Path=/; Domain=hermes.example.ts.net; Secure" in values)
    }

    @Test
    fun setCookieValuesSkipWhatDoesNotMatch() = runTest {
        storage.addCookie(https, Cookie("a", "1", path = "/", secure = true))
        // A secure session cookie never goes to a plain-http view, and an unreadable URL gets nothing.
        assertTrue(storage.setCookieValues("http://hermes.example.ts.net/").isEmpty())
        assertTrue(storage.setCookieValues("not a url").isEmpty())
    }

    @Test
    fun setCookieValuesCarryCookiesForOtherPathsOnTheHost() = runTest {
        // The page is opened at its own path but calls the gateway's API under /api, so an /api-scoped
        // session cookie must reach the view's jar too, still scoped to /api.
        storage.addCookie(https, Cookie("api_only", "v", path = "/api", secure = true))
        val values = storage.setCookieValues("https://hermes.example.ts.net/example-board")
        assertEquals(listOf("api_only=v; Path=/api; Secure"), values)
    }

    @Test
    fun webJarSendsTheAccessTokenOverHttpsOnly() = runTest {
        val access = AccessTokens(InMemoryKeyValueStore())
        access.set("hermes.example.ts.net", AccessToken("id", "secret"))
        val jar = WebCookieJar(storage, access)

        assertEquals(
            mapOf(CF_ACCESS_CLIENT_ID to "id", CF_ACCESS_CLIENT_SECRET to "secret"),
            jar.headersFor("https://hermes.example.ts.net/example-board"),
        )
        assertTrue(jar.headersFor("http://hermes.example.ts.net/example-board").isEmpty())
        assertTrue(jar.headersFor("https://other.ts.net/example-board").isEmpty())
    }
}

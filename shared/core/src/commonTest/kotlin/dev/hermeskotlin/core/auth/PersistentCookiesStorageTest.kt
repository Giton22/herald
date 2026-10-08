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
}

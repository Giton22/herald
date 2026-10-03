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
}

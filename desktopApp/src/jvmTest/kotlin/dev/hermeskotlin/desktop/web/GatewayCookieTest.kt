package dev.hermeskotlin.desktop.web

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's session as a plugin page's Chromium gets it, and which pages count as the gateway's own. */
class GatewayCookieTest {

    @Test
    fun aSessionCookieKeepsItsAttributes() {
        val before = System.currentTimeMillis()
        val cookie = assertNotNull(parseSetCookie("session=abc=123; Path=/api; Secure; HttpOnly; Max-Age=60"))
        assertEquals("session", cookie.name)
        // Only the first = splits: the value may hold more.
        assertEquals("abc=123", cookie.value)
        assertEquals("/api", cookie.path)
        assertTrue(cookie.secure)
        assertTrue(cookie.httponly)
        assertTrue(cookie.hasExpires)
        val expires = assertNotNull(cookie.expires).time
        assertTrue(expires >= before + 60_000 && expires <= System.currentTimeMillis() + 60_000)
    }

    @Test
    fun aCookieWithoutDomainStaysTheHostsAndLastsTheSession() {
        val cookie = assertNotNull(parseSetCookie("token=xyz"))
        assertEquals("", cookie.domain)
        assertEquals("/", cookie.path)
        assertFalse(cookie.secure)
        assertFalse(cookie.hasExpires)
    }

    @Test
    fun aDomainCoversItsSubdomains() {
        assertEquals(".example.com", parseSetCookie("a=b; Domain=example.com")?.domain)
        assertEquals(".example.com", parseSetCookie("a=b; domain=.example.com")?.domain)
    }

    @Test
    fun somethingThatIsNoCookieIsLeftOut() {
        assertNull(parseSetCookie(""))
        assertNull(parseSetCookie("novalue"))
        assertNull(parseSetCookie("=value"))
    }

    @Test
    fun theGatewaysOriginIgnoresDefaultPortsAndCase() {
        val gateway = URI("https://Hermes.example.com/")
        assertTrue(sameOrigin(URI("https://hermes.example.com:443/plugins/x"), gateway))
        assertFalse(sameOrigin(URI("http://hermes.example.com/"), gateway))
        assertFalse(sameOrigin(URI("https://hermes.example.com:8443/"), gateway))
        assertFalse(sameOrigin(URI("https://evil.example.com/"), gateway))
    }
}

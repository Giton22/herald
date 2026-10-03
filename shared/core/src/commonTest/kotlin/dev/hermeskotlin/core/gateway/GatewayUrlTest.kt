package dev.hermeskotlin.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayUrlTest {

    @Test
    fun schemeLessHostPortGetsHttp() {
        assertEquals("http://100.64.0.1:9119", GatewayUrl.parse("100.64.0.1:9119").value)
        assertEquals("http://homelab:9119", GatewayUrl.parse("  homelab:9119  ").value)
    }

    @Test
    fun trailingSlashesQueryAndFragmentAreDropped() {
        assertEquals("https://hermes.example.com", GatewayUrl.parse("https://hermes.example.com///").value)
        assertEquals("http://host:9119/prefix", GatewayUrl.parse("http://host:9119/prefix/?a=1#x").value)
    }

    @Test
    fun nonHttpSchemesAreRejected() {
        assertFailsWith<InvalidGatewayUrlException> { GatewayUrl.parse("ftp://host") }
        assertFailsWith<InvalidGatewayUrlException> { GatewayUrl.parse("file:///etc/passwd") }
    }

    @Test
    fun onlyPlainHttpToAPublicHostIsExposed() {
        assertTrue(GatewayUrl.parse("http://203.0.113.5:9119").isExposed)
        assertTrue(GatewayUrl.parse("http://hermes.example.com").isExposed)
        assertFalse(GatewayUrl.parse("https://hermes.example.com").isExposed)
        assertFalse(GatewayUrl.parse("100.64.0.1:9119").isExposed)
        assertFalse(GatewayUrl.parse("http://hermes.example.ts.net").isExposed)
        assertFalse(GatewayUrl.parse("192.168.1.20:9119").isExposed)
        assertFalse(GatewayUrl.parse("http://localhost:9119").isExposed)
        assertFalse(GatewayUrl.parse("http://homeserver:9119").isExposed)
        assertTrue(GatewayUrl.parse("http://100.128.0.1").isExposed) // just past Tailscale's range
    }

    @Test
    fun blankIsRejected() {
        assertFailsWith<InvalidGatewayUrlException> { GatewayUrl.parse("   ") }
        assertNull(GatewayUrl.parseOrNull(""))
    }

    @Test
    fun webSocketUrlFollowsScheme() {
        assertEquals("ws://host:9119/api/ws", GatewayUrl.parse("host:9119").webSocketUrl)
        assertEquals("wss://hermes.example.com/api/ws", GatewayUrl.parse("https://hermes.example.com").webSocketUrl)
        assertEquals("wss://h.example.com/p/api/ws", GatewayUrl.parse("https://h.example.com/p/").webSocketUrl)
    }

    @Test
    fun resolveJoinsPaths() {
        assertEquals("http://host:9119/api/status", GatewayUrl.parse("host:9119").resolve("/api/status"))
    }
}

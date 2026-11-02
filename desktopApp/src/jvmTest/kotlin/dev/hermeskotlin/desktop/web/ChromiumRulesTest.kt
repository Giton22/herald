package dev.hermeskotlin.desktop.web

import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which addresses Chromium may reach, and which keys a page shares with the app. */
class ChromiumRulesTest {

    @Test
    fun aGatewayIsItsHostAndPort() {
        assertEquals("hermes.example.com:443", hostPort("https://Hermes.example.com/plugins/kanban"))
        assertEquals("hermes.example.com:80", hostPort("http://hermes.example.com"))
        assertEquals("100.64.0.1:9119", hostPort("http://100.64.0.1:9119/"))
    }

    @Test
    fun onlyWebAddressesReachAnything() {
        assertNull(hostPort("ftp://hermes.example.com:21/"))
        assertNull(hostPort("file:///C:/secret.txt"))
        assertNull(hostPort("mailto:someone@example.com"))
        assertNull(hostPort("not a url"))
    }

    @Test
    fun aQueryUriRefusesStillLeavesTheAddress() {
        val uri = assertNotNull(parseUrl("https://hermes.example.com/x?filter={a|b}#top"))
        assertEquals("hermes.example.com", uri.host)
        assertEquals("/x", uri.path)
        assertEquals("hermes.example.com:443", hostPort("https://hermes.example.com/x?filter={a|b}"))
    }

    @Test
    fun editingKeysStayWithThePage() {
        assertFalse(reachesApp(key(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)))
        assertFalse(reachesApp(key(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)))
        assertFalse(reachesApp(key(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)))
        assertFalse(reachesApp(key(KeyEvent.VK_K)))
        assertFalse(reachesApp(key(KeyEvent.VK_ENTER)))
    }

    @Test
    fun theAppsShortcutsAndEscapeReachTheApp() {
        assertTrue(reachesApp(key(KeyEvent.VK_ESCAPE)))
        assertTrue(reachesApp(key(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK)))
        assertTrue(reachesApp(key(KeyEvent.VK_W, InputEvent.META_DOWN_MASK)))
    }

    private fun key(code: Int, modifiers: Int = 0) =
        KeyEvent(Canvas(), KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED)
}

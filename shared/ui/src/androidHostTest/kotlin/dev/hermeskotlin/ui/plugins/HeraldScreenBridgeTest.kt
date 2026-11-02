package dev.hermeskotlin.ui.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class HeraldScreenBridgeTest {
    @Test fun screenWithoutNativeHostNeverLoadsRegardlessOfClipboardSetting() {
        for (clipboardEnabled in listOf(false, true)) {
            assertEquals(false, mayLoadPluginDocument(screenPage = true, nativeHostInstalled = false), "clipboard=$clipboardEnabled")
            assertEquals(true, mayLoadPluginDocument(screenPage = true, nativeHostInstalled = true))
            assertEquals(true, mayLoadPluginDocument(screenPage = false, nativeHostInstalled = false))
        }
    }
    @Test fun clipboardSettingIsReadForEveryRequestAndCloseAdmissionRemains() {
        val gateway = "https://example.test/gw"
        val page = "$gateway/api/plugins/herald-screen/index.html"
        var enabled = true
        val gate = ScreenBridgeGate(gateway) { enabled }
        gate.documentReady(page)
        for (operation in listOf("clipboard.readText", "clipboard.writeText")) {
            gate.touch(page, 100)
            assertEquals(true, gate.consumeClipboardGesture(101), operation)
            enabled = false
            gate.touch(page, 200)
            assertEquals(false, gate.consumeClipboardGesture(201), operation)
            assertEquals(true, gate.admits(page, gateway, true)) // viewer.close still admitted
            assertEquals(false, gate.admits("$gateway/other", gateway, true))
            enabled = true
            gate.touch(page, 300)
            assertEquals(true, gate.consumeClipboardGesture(301), operation)
        }
    }

    @Test fun exactScreenDocumentRetainsAllAdmissionGates() {
        val gateway = "https://example.test:8443/gw"
        for (plugin in listOf("herald-screen")) {
            val page = "$gateway/api/plugins/$plugin/index.html"
            val gate = ScreenBridgeGate(gateway)
            assertEquals(true, isHeraldScreenUrl(page, gateway))
            assertEquals(false, gate.admits(page, gateway, true))
            gate.documentReady(page)
            assertEquals(true, gate.admits(page, gateway, true))
            assertEquals(false, gate.admits(page, gateway, false))
            assertEquals(false, gate.admits(page, "https://evil.test:8443", true))
            assertEquals(false, gate.admits(page, "https://example.test", true))
            assertEquals(false, gate.consumeClipboardGesture(100))
            gate.touch(page, 100)
            assertEquals(true, gate.consumeClipboardGesture(101))
            assertEquals(false, gate.consumeClipboardGesture(102))
            gate.touch(page, 200)
            gate.navigationStarted()
            assertEquals(false, gate.admits(page, gateway, true))
            gate.documentReady(page)
            assertEquals(false, gate.consumeClipboardGesture(201))
            gate.dispose()
            assertEquals(false, gate.admits(page, gateway, true))
        }
    }

    @Test fun screenTestLookalikesAndAlteredUrlsAreRejected() {
        val gateway = "https://example.test/gw"
        for (suffix in listOf(
            "herald-screen-test/index.html", "herald-screen-testing/index.html", "herald-screen-test2/index.html",
            "herald-screen-test/index.html/extra", "herald-screen-test/index.html?x=1",
            "herald-screen-test/index.html#fragment", "herald-screen-test/%69ndex.html",
            "herald-screen-test/../herald-screen/index.html"
        )) {
            assertEquals(false, isHeraldScreenUrl("$gateway/api/plugins/$suffix", gateway), suffix)
        }
        assertEquals(false, isHeraldScreenUrl("https://evil.test/gw/api/plugins/herald-screen-test/index.html", gateway))
        assertEquals(false, isHeraldScreenUrl("http://example.test/gw/api/plugins/herald-screen-test/index.html", gateway))
    }
    @Test fun testPluginNeverReceivesScreenBridgeAuthority() {
        val gateway = "https://example.test/gw"
        val testPage = "$gateway/api/plugins/herald-screen-test/index.html"
        val screenPage = "$gateway/api/plugins/herald-screen/index.html"
        val gate = ScreenBridgeGate(gateway)
        gate.documentReady(testPage)
        gate.touch(testPage, 100)
        assertEquals(false, gate.admits(testPage, gateway, true))
        assertEquals(false, gate.consumeClipboardGesture(101))
        gate.documentReady(screenPage)
        gate.touch(testPage, 200)
        assertEquals(false, gate.admits(testPage, gateway, true))
        assertEquals(false, gate.consumeClipboardGesture(201))
    }
    @Test fun exactTrustedDocumentOnly() {
        val gateway = "https://example.test/gw"
        assertEquals(true, isHeraldScreenUrl("https://example.test/gw/api/plugins/herald-screen/index.html", gateway))
        listOf("https://evil.test/gw/api/plugins/herald-screen/index.html", "https://example.test/gw/api/plugins/other/index.html", "https://example.test/gw/api/plugins/herald-screen/index.html/extra", "https://example.test/gw/api/plugins/herald-screen/index.html?x=1", "http://example.test/gw/api/plugins/herald-screen/index.html").forEach {
            assertEquals(false, isHeraldScreenUrl(it, gateway), it)
        }
        assertEquals(true, isHeraldScreenUrl("https://example.test:443/gw/api/plugins/herald-screen/index.html", gateway))
    }

    @Test fun protocolAcceptsOnlyBoundedKnownRequests() {
        assertEquals(ScreenRequest("a", "viewer.close", null), parseScreenRequest("{\"id\":\"a\",\"operation\":\"viewer.close\"}"))
        assertNotNull(parseScreenRequest("{\"id\":\"b\",\"operation\":\"clipboard.writeText\",\"text\":\"hello\"}"))
        assertNull(parseScreenRequest("not json"))
        assertNull(parseScreenRequest("{\"id\":\"x\",\"operation\":\"unknown\"}"))
        assertNull(parseScreenRequest("{\"id\":\"\",\"operation\":\"viewer.close\"}"))
        assertNull(parseScreenRequest("{\"id\":\"x\",\"operation\":\"clipboard.writeText\",\"text\":\"${"é".repeat(MAX_CLIPBOARD_BYTES / 2 + 1)}\"}"))
    }

    @Test fun nonDefaultPortIsRetainedThroughRequestAdmission() {
        val gateway = "http://example.test:8765"
        val page = "$gateway/api/plugins/herald-screen/index.html"
        val gate = ScreenBridgeGate(gateway)
        gate.documentReady(page)
        assertEquals(true, gate.admits(page, gateway, true))
        assertEquals(false, gate.admits(page, "http://example.test", true))
        assertEquals(false, gate.admits(page, gateway, false))
        assertEquals(false, gate.admits("$gateway/api/plugins/other/index.html", gateway, true))
    }

    @Test fun clipboardRequiresOneRecentNativeGesture() {
        val gateway = "https://example.test:8443/gw"
        val page = "$gateway/api/plugins/herald-screen/index.html"
        val gate = ScreenBridgeGate(gateway)
        gate.documentReady(page)
        assertEquals(false, gate.consumeClipboardGesture(100))
        gate.touch(page, 100)
        assertEquals(true, gate.consumeClipboardGesture(4_100)) // delayed protected Copy
        assertEquals(false, gate.consumeClipboardGesture(4_101))
        gate.touch(page, 5_000)
        assertEquals(false, gate.consumeClipboardGesture(10_001))
        gate.touch(page, 20_000)
        assertEquals(false, gate.consumeClipboardGesture(19_999))
    }

    @Test fun navigationAndDisposalRevokePendingClipboardAuthority() {
        val gateway = "http://example.test:8765"
        val page = "$gateway/api/plugins/herald-screen/index.html"
        val gate = ScreenBridgeGate(gateway)
        gate.documentReady(page)
        gate.touch(page, 100)
        gate.navigationStarted()
        assertEquals(false, gate.admits(page, gateway, true))
        gate.documentReady(page)
        assertEquals(false, gate.consumeClipboardGesture(200))
        gate.touch(page, 300)
        gate.dispose()
        gate.documentReady(page)
        assertEquals(false, gate.admits(page, gateway, true))
        assertEquals(false, gate.consumeClipboardGesture(400))
    }

    @Test fun untrustedNavigationRevokesUntilTrustedDocumentReloads() {
        val gateway = "https://example.test/gw"
        val page = "$gateway/api/plugins/herald-screen/index.html"
        val gate = ScreenBridgeGate(gateway)
        gate.documentReady(page)
        gate.touch(page, 100)
        gate.navigationStarted()
        gate.documentReady("$gateway/login")
        assertEquals(false, gate.admits(page, gateway, true))
        assertEquals(false, gate.consumeClipboardGesture(200))
        gate.navigationStarted()
        gate.documentReady(page)
        assertEquals(true, gate.admits(page, gateway, true))
        assertEquals(false, gate.consumeClipboardGesture(300))
        gate.touch(page, 400)
        assertEquals(true, gate.consumeClipboardGesture(401))
    }

    @Test fun requestsAreStringsOnlyAndEscapeExpansionDoesNotShrinkTheTextLimit() {
        for (raw in listOf("{\"id\":1,\"operation\":\"viewer.close\"}", "{\"id\":\"x\",\"operation\":\"clipboard.writeText\",\"text\":true}", "{\"id\":\"x\",\"operation\":\"viewer.close\",\"other\":1}", "{\"id\":\"x\",\"operation\":\"clipboard.readText\",\"text\":\"unexpected\"}")) {
            assertNull(parseScreenRequest(raw), raw)
        }
        val escaped = "\\u0001".repeat(MAX_CLIPBOARD_BYTES)
        val accepted = parseScreenRequest("{\"id\":\"x\",\"operation\":\"clipboard.writeText\",\"text\":\"$escaped\"}")
        assertNotNull(accepted)
        assertEquals(MAX_CLIPBOARD_BYTES, accepted.text?.length)
    }

    @Test fun nonHttpOrEncodedDocumentPathsAreNotTrusted() {
        assertEquals(false, isHeraldScreenUrl("file:///api/plugins/herald-screen/index.html", "file:///"))
        assertEquals(false, isHeraldScreenUrl("https://example.test/api%2fplugins/herald-screen/index.html", "https://example.test"))
        assertEquals(false, isHeraldScreenUrl("https://user@example.test/api/plugins/herald-screen/index.html", "https://example.test"))
    }
}

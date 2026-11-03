package dev.hermeskotlin.core.plugins

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class GitPluginInstallerTest {
    private val sha = "0123456789aBcDeF0123456789abcdef01234567"
    private val spec get() = GitPluginSpec("herald-screen", "https://example.org/screen.git#herald-screen", sha)
    private suspend fun TestScope.connect(t: FakeTransport): GatewayConnection {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val engine = MockEngine { respond("""{"ticket":"T","ttl_seconds":30}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        val c = GatewayConnection(AuthApi(createHttpClient(engine, cookies), cookies), { _, _ -> t }, backgroundScope)
        c.start(GatewayUrl.parse("https://a.example")); t.push(FakeTransport.READY)
        c.state.first { it is ConnectionState.Connected }; return c
    }
    private fun TestScope.server(t: FakeTransport, handler: (JsonObject) -> String?) {
        backgroundScope.launch {
            var n = 0
            t.sent.collect { frames -> while (n < frames.size) {
                val frame = frames[n++]; val reply = handler(frame)
                if (reply != null) t.push("""{"jsonrpc":"2.0","id":${frame["id"]},"result":$reply}""")
            } }
        }
    }
    private fun JsonObject.params() = this["params"]!!.jsonObject
    private fun JsonObject.action() = params()["action"]?.jsonPrimitive?.content
    private fun installer(c: GatewayConnection, valid: () -> Boolean = { true }) = GitPluginInstaller(c, (c.state.value as ConnectionState.Connected).client, valid)
    @Test fun acceptsPluginSubdirectoriesWithoutChangingInputs() {
        for (url in listOf(
            "https://example.org/screen.git",
            "https://example.org/screen.git#herald-screen",
            "https://example.org/screen.git#plugins/herald-screen",
            "https://example.org:8443/owner/screen.git#plugins/v1.2_~x"
        )) {
            val source = GitPluginSpec("screen", url, sha)
            assertEquals(url, source.repository)
            assertEquals(sha, source.revision)
        }
    }
    @Test fun rejectsUnsafeInput() {
        for (url in listOf(
            "http://example.org/x", "https://u:p@example.org/x", "https://u@example.org/x",
            "https://example.org/../x", "https://example.org/./x", "https://example.org//x",
            "https://example.org/x/", "https://example.org/x?foo=bar", "https://example.org/x?foo=bar#plugin",
            "https://example.org/x#", "https://example.org/x#/plugin", "https://example.org/x#plugin/",
            "https://example.org/x#plugin//nested", "https://example.org/x#.", "https://example.org/x#..",
            "https://example.org/x#plugin/./nested", "https://example.org/x#plugin/../nested",
            "https://example.org/x#plugin#other", "https://example.org/x#plugin?ref=main",
            "https://example.org/x#%2e%2e/plugin", "https://example.org/x%23plugin",
            "https://example.org/x#plugin%2fnested", "https://example.org/x#plugin\\nested",
            "https://example.org/x\\plugin#nested"
        )) assertFailsWith<IllegalArgumentException>(url) { GitPluginSpec("screen", url, sha) }
        for (ref in listOf("main", sha.dropLast(1), "$sha#herald-screen", "$sha\n"))
            assertFailsWith<IllegalArgumentException>(ref) { GitPluginSpec("screen", "https://example.org/x#plugin", ref) }
        assertFailsWith<IllegalArgumentException> { GitPluginSpec("../screen", "https://example.org/x", sha) }
    }
    @Test fun installAndSeparateEnableUseLaunchProfileAndPinReadback() = runTest {
        val t = FakeTransport(); val c = connect(t); var status: String? = null
        server(t) { f -> when (f.action()) {
            "list" -> """{"plugins":[${status?.let { """{"name":"herald-screen","key":"herald-screen","status":"$it","pinned_sha":"$sha"}""" } ?: ""}]}"""
            "install" -> { assertEquals("https://example.org/screen.git#herald-screen", f.params()["identifier"]!!.jsonPrimitive.content); assertEquals(JsonPrimitive(false), f.params()["force"]); assertEquals(JsonPrimitive(false), f.params()["enable"]); assertEquals(sha, f.params()["ref"]!!.jsonPrimitive.content); assertNull(f.params()["profile"]); status = "not enabled"; """{"ok":true,"plugin_name":"herald-screen","enabled":false}""" }
            "toggle" -> { assertEquals(JsonPrimitive(true), f.params()["enable"]); status = "enabled"; """{"ok":true}""" }
            else -> null
        } }
        val i = installer(c)
        assertEquals(GitInstallResult.Reported("not enabled"), i.install(spec))
        assertEquals(GitInstallResult.Reported("enabled"), i.enable(spec))
        assertIs<GitInstallResult.Existing>(i.install(spec))
    }
    @Test fun oneClickInstallsAndEnablesOrEnablesExistingWithoutReplacement() = runTest {
        for (initial in listOf<String?>(null, "disabled", "enabled")) {
            val t = FakeTransport(); val c = connect(t); var status = initial
            server(t) { f -> when (f.action()) {
                "list" -> """{"plugins":[${status?.let { """{"name":"herald-screen","key":"herald-screen","status":"$it","pinned_sha":"$sha"}""" } ?: ""}]}"""
                "install" -> { assertEquals(spec.repository, f.params()["identifier"]!!.jsonPrimitive.content); assertEquals(sha, f.params()["ref"]!!.jsonPrimitive.content); assertEquals(JsonPrimitive(false), f.params()["force"]); assertEquals(JsonPrimitive(false), f.params()["enable"]); assertNull(f.params()["profile"]); status = "disabled"; """{"ok":true,"plugin_name":"herald-screen"}""" }
                "toggle" -> { assertEquals(JsonPrimitive(true), f.params()["enable"]); assertNull(f.params()["profile"]); status = "enabled"; """{"ok":true}""" }
                else -> null
            } }
            assertEquals(GitInstallResult.Reported("enabled"), installer(c).setup(spec))
            val actions = t.sent.value.mapNotNull { it.action() }.filter { it != "list" }
            assertEquals(when (initial) { null -> listOf("install", "toggle"); "disabled" -> listOf("toggle"); else -> emptyList() }, actions)
            c.stop()
        }
    }
    @Test fun oneClickRefusesUnverifiedDisabledAndCaseVariantPlugins() = runTest {
        for (row in listOf(
            """{"name":"herald-screen","status":"disabled"}""",
            """{"name":"herald-screen","status":"disabled","pinned_sha":"${"a".repeat(40)}"}""",
            """{"name":"Herald-Screen","status":"disabled","pinned_sha":"$sha"}""",
            """{"name":"other","key":"HERALD-SCREEN","status":"disabled","pinned_sha":"$sha"}"""
        )) {
            val t = FakeTransport(); val c = connect(t)
            server(t) { """{"plugins":[$row]}""" }
            assertIs<GitInstallResult.Existing>(installer(c).setup(spec))
            assertTrue(t.sent.value.none { it.action() == "install" || it.action() == "toggle" })
            c.stop()
        }
    }
    @Test fun oneClickMismatchedPinNeverEnables() = runTest {
        val t = FakeTransport(); val c = connect(t); var installed = false
        server(t) { f -> when (f.action()) {
            "list" -> if (!installed) """{"plugins":[]}""" else """{"plugins":[{"name":"herald-screen","status":"disabled","pinned_sha":"${"a".repeat(40)}"}]}"""
            "install" -> { installed = true; """{"ok":true,"plugin_name":"herald-screen"}""" }
            else -> null
        } }
        assertIs<GitInstallResult.Uncertain>(installer(c).setup(spec))
        assertTrue(t.sent.value.none { it.action() == "toggle" })
    }
    @Test fun unknownPinAndIdentityNeverEnable() = runTest {
        for (mode in listOf("missing", "mismatch", "identity", "enabled")) {
            val t = FakeTransport(); val c = connect(t); var written = false
            server(t) { f -> when (f.action()) {
                "list" -> if (!written) """{"plugins":[]}""" else {
                    val pin = when(mode) { "missing" -> ""; "mismatch" -> ",\"pinned_sha\":\"${"a".repeat(40)}\""; else -> ",\"pinned_sha\":\"$sha\"" }
                    """{"plugins":[{"name":"herald-screen","status":"${if (mode == "enabled") "enabled" else "not enabled"}"$pin}]}"""
                }
                "install" -> { written = true; """{"ok":true,"plugin_name":"${if (mode == "identity") "other" else "herald-screen"}"}""" }
                else -> null
            } }
            val i = installer(c)
            assertIs<GitInstallResult.Uncertain>(i.install(spec), mode)
            assertIs<GitInstallResult.Refused>(i.enable(spec), mode)
            assertTrue(t.sent.value.none { it.action() == "toggle" }); c.stop()
        }
    }
    @Test fun refusesStaleGatewayAndExistingAlias() = runTest {
        val t = FakeTransport(); val c = connect(t)
        server(t) { """{"plugins":[{"name":"other","key":"herald-screen","status":"enabled"}]}""" }
        assertIs<GitInstallResult.Existing>(installer(c).install(spec))
        assertIs<GitInstallResult.Refused>(installer(c) { false }.install(spec))
        assertTrue(t.sent.value.none { it.action() == "install" })
    }
    @Test fun changedTargetAfterReadDoesNotWrite() = runTest {
        val t = FakeTransport(); val c = connect(t); var valid = true
        server(t) { valid = false; """{"plugins":[]}""" }
        assertIs<GitInstallResult.Refused>(installer(c) { valid }.install(spec))
        assertTrue(t.sent.value.none { it.action() == "install" })
    }
    @Test fun lostInstallReplyIsUncertainAndNeverEnables() = runTest {
        val t = FakeTransport(); val c = connect(t)
        server(t) { f -> if (f.action() == "list") """{"plugins":[]}""" else null }
        val i = installer(c)
        assertIs<GitInstallResult.Uncertain>(i.install(spec))
        assertIs<GitInstallResult.Refused>(i.enable(spec))
        assertTrue(t.sent.value.none { it.action() == "toggle" })
    }
}

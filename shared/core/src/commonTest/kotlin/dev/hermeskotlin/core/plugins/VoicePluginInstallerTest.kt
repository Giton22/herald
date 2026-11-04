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

class VoicePluginInstallerTest {
    private val revision = "0123456789abcdef0123456789abcdef01234567"

    private suspend fun TestScope.connect(transport: FakeTransport): GatewayConnection {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val engine = MockEngine { respond("""{"ticket":"T","ttl_seconds":30}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        val connection = GatewayConnection(AuthApi(createHttpClient(engine, cookies), cookies), { _, _ -> transport }, backgroundScope)
        connection.start(GatewayUrl.parse("https://a.example"))
        transport.push(FakeTransport.READY)
        connection.state.first { it is ConnectionState.Connected }
        return connection
    }

    private fun TestScope.respond(transport: FakeTransport, scoped: Boolean = true, reply: suspend (JsonObject) -> String?) {
        backgroundScope.launch {
            var n = 0
            transport.sent.collect { frames ->
                while (n < frames.size) {
                    val frame = frames[n++]
                    val profile = (frame["params"] as? JsonObject)?.get("profile")?.jsonPrimitive?.content
                    if (scoped && frame.method() == "plugins.manage" && profile?.startsWith("kyllene_scope_probe") == true) {
                        transport.push("""{"jsonrpc":"2.0","id":${frame["id"]},"error":{"code":4064,"message":"profile '$profile' not found"}}""")
                        continue
                    }
                    val result = reply(frame) ?: continue
                    transport.push("""{"jsonrpc":"2.0","id":${frame["id"]},"result":$result}""")
                }
            }
        }
    }
    private fun JsonObject.method() = this["method"]?.jsonPrimitive?.content
    private fun JsonObject.params() = this["params"]!!.jsonObject
    private fun JsonObject.action() = params()["action"]?.jsonPrimitive?.content
    private val roster = """{"profiles":[{"name":"existing"},{"name":"new"},{"name":"other"}]}"""

    @Test fun scopedLegacyGatewayIgnoringRefCannotInstall() = runTest {
        val t = FakeTransport(); val connection = connect(t)
        var installedHead = false
        // Legacy behavior: the profile probe succeeds, but install ignores ref and uses HEAD.
        respond(t) { f -> when (f.method()) {
            "profiles.list" -> roster
            "plugins.manage" -> if (f.action() == "install") {
                installedHead = true; """{"ok":true}"""
            } else """{"plugins":[]}"""
            else -> null
        } }
        val installer = VoicePluginInstaller(connection, revision)
        assertFailsWith<IllegalArgumentException> { VoicePluginInstaller(connection, "main") }
        assertFailsWith<IllegalStateException> { installer.profiles() }
        assertTrue(t.sent.value.any { it.actionSafe() == "list" })
        assertFalse(installedHead)
        assertTrue(t.sent.value.none { it.actionSafe() == "install" })
    }

    @Test fun unscopedGatewayIsRejectedWithoutWrites() = runTest {
        val t = FakeTransport(); val c = connect(t)
        respond(t, scoped = false) { f -> when (f.method()) {
            "profiles.list" -> roster
            "plugins.manage" -> """{"plugins":[]}"""
            else -> null
        } }
        assertFailsWith<IllegalStateException> { VoicePluginInstaller(c, revision).profiles() }
        assertTrue(t.sent.value.none { it.actionSafe() == "install" })
    }

    @Test fun supportedGatewayInstallsOnlySelectedAndChecksReadback() = runTest {
        val t = FakeTransport(); val c = connect(t); val installed = mutableSetOf<String>()
        respond(t) { f -> when (f.method()) {
            "profiles.list" -> roster
            "plugins.manage" -> {
                val p = f.params()["profile"]!!.jsonPrimitive.content
                if (f.action() == "install") {
                    assertEquals(revision, f.params()["ref"]!!.jsonPrimitive.content)
                    assertEquals(JsonPrimitive(false), f.params()["force"])
                    installed += p; """{"ok":true}"""
                } else {
                    val push = if (p == "existing") """{"name":"herald-push","pinned_sha":"$revision"}""" else ""
                    val voice = if (p in installed) """{"name":"kyllene_voice","pinned_sha":"${if (p == "other") "a".repeat(40) else revision}"}""" else ""
                    """{"plugins":[${listOf(push, voice).filter { it.isNotEmpty() }.joinToString()}]}"""
                }
            }
            else -> null
        } }
        val installer = VoicePluginInstaller(c, revision); val snapshot = installer.profiles()
        assertFailsWith<IllegalArgumentException> { installer.install(snapshot, listOf("unknown")) }
        val result = installer.install(snapshot, listOf("new", "other", "new"))
        assertIs<VoiceInstallResult.Installed>(result["new"])
        assertIs<VoiceInstallResult.Unknown>(result["other"])
        assertEquals(setOf("new", "other"), installed)
        assertIs<VoiceInstallResult.Existing>(installer.install(snapshot, listOf("new"))["new"])
        c.stop()
        assertIs<VoiceInstallResult.Skipped>(installer.install(snapshot, listOf("existing"))["existing"])
    }

    @Test fun missingMalformedAndAmbiguousEvidenceAreRejected() = runTest {
        for (rows in listOf("", """{"name":"herald-push"}""", """{"name":"herald-push","pinned_sha":"main"}""", """{"name":"herald-push","pinned_sha":"$revision"},{"name":"herald-push","pinned_sha":"$revision"}""")) {
            val t = FakeTransport(); val c = connect(t)
            respond(t) { f -> when (f.method()) {
                "profiles.list" -> roster
                "plugins.manage" -> """{"plugins":[$rows]}"""
                else -> null
            } }
            assertFailsWith<IllegalStateException> { VoicePluginInstaller(c, revision).profiles() }
            assertTrue(t.sent.value.none { it.actionSafe() == "install" }); c.stop()
        }
    }

    private fun JsonObject.actionSafe() = (this["params"] as? JsonObject)?.get("action")?.jsonPrimitive?.content
}

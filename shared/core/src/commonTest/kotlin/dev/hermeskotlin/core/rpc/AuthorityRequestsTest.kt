package dev.hermeskotlin.core.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a gateway running its chats on the session authority gets from [JsonRpcClient], and what an older one gets. */
class AuthorityRequestsTest {

    /** A client on [transport], greeted by a gateway that says whether it runs on the session [authority]. */
    private suspend fun TestScope.connect(transport: FakeTransport, authority: Boolean): JsonRpcClient {
        val client = JsonRpcClient(transport)
        backgroundScope.launch { runCatching { client.run() } }
        transport.push(if (authority) AUTHORITY_READY else FakeGateway.QUIET_READY)
        client.ready.await()
        return client
    }

    /** Sends [method] with [params], answers it with [result] and returns the params as they went out. */
    private suspend fun CoroutineScope.call(
        client: JsonRpcClient,
        transport: FakeTransport,
        method: String,
        params: JsonObject,
        result: String = "{}",
    ): JsonObject {
        val before = transport.sent.value.size
        val reply = async { client.request(method, params) }
        val sent = transport.sent.first { list -> list.drop(before).any { it.method() == method } }
            .drop(before).first { it.method() == method }
        transport.push("""{"jsonrpc":"2.0","id":"${sent["id"]!!.jsonPrimitive.content}","result":$result}""")
        reply.await()
        return sent["params"]!!.jsonObject
    }

    private fun JsonObject.method() = this["method"]?.jsonPrimitive?.content
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun anOlderGatewayGetsRequestsAsTheyAre() = runTest {
        val transport = FakeTransport()
        val client = connect(transport, authority = false)
        assertFalse(client.sessionAuthority)

        val create = call(client, transport, "session.create", kylleneCreate())
        assertEquals(kylleneCreate(), create)
        val submit = call(client, transport, "prompt.submit", buildJsonObject {
            put("session_id", "rt1")
            put("text", "hi")
        })
        assertNull(submit["submission_id"])
    }

    @Test
    fun theSessionAuthorityGetsACreateItsContractTakes() = runTest {
        val transport = FakeTransport()
        val client = connect(transport, authority = true)
        assertTrue(client.sessionAuthority)

        val create = call(client, transport, "session.create", kylleneCreate())

        // Every key is one tui_gateway/contracts/canonical_sessions.py CanonicalCreateParams declares.
        assertTrue(CANONICAL_CREATE_KEYS.containsAll(create.keys), "${create.keys - CANONICAL_CREATE_KEYS}")
        assertEquals("gui", create.text("source"))
        assertEquals("high", create.text("reasoning"))
        assertEquals("priority", create.text("service_tier"))
        assertNotNull(create.text("request_id"))
        // What it has the same names for goes as it is.
        assertEquals("/home/me/project", create.text("cwd"))
        assertEquals("test-model", create.text("model"))
        assertEquals("work", create.text("profile"))
    }

    @Test
    fun aFastPickTurnedOffAsksForTheNormalTier() = runTest {
        val transport = FakeTransport()
        val client = connect(transport, authority = true)

        val create = call(client, transport, "session.create", buildJsonObject {
            put("source", "desktop")
            put("fast", false)
        })

        assertEquals("normal", create.text("service_tier"))
        assertNull(create["fast"])
    }

    @Test
    fun aSendCarriesASubmissionIdAndKeepsOneItHas() = runTest {
        val transport = FakeTransport()
        val client = connect(transport, authority = true)

        val first = call(client, transport, "prompt.submit", buildJsonObject {
            put("session_id", "rt1")
            put("text", "hi")
        })
        val second = call(client, transport, "prompt.submit", buildJsonObject {
            put("session_id", "rt1")
            put("text", "again")
        })
        val given = call(client, transport, "prompt.submit", buildJsonObject {
            put("session_id", "rt1")
            put("text", "again")
            put("submission_id", "mine")
        })

        assertNotNull(first.text("submission_id"))
        // Each send is its own: the gateway answers a repeated id with the first one's receipt.
        assertTrue(first.text("submission_id") != second.text("submission_id"))
        assertEquals("mine", given.text("submission_id"))
    }

    @Test
    fun everyRequestAboutANamedProfilesChatCarriesThatProfile() = runTest {
        val transport = FakeTransport()
        val client = connect(transport, authority = true)
        call(client, transport, "session.resume", buildJsonObject {
            put("session_id", "stored-w")
            put("profile", "work")
            put("source", "desktop")
        }, result = """{"session_id":"rt-w","running":false}""")
        call(client, transport, "session.create", buildJsonObject { put("source", "desktop") }, result = """{"session_id":"rt-d"}""")

        // The gateway routed the named profile's chat to that profile's own connection; only it can take the send.
        val submit = call(client, transport, "prompt.submit", buildJsonObject {
            put("session_id", "rt-w")
            put("text", "hi")
        })
        val interrupt = call(client, transport, "session.interrupt", buildJsonObject { put("session_id", "rt-w") })
        val launchProfile = call(client, transport, "prompt.submit", buildJsonObject {
            put("session_id", "rt-d")
            put("text", "hi")
        })

        assertEquals("work", submit.text("profile"))
        assertEquals("work", interrupt.text("profile"))
        assertNull(launchProfile["profile"])
    }

    @Test
    fun anOlderGatewayGetsNoProfileItWasNotSent() = runTest {
        val transport = FakeTransport()
        val client = connect(transport, authority = false)
        call(client, transport, "session.resume", buildJsonObject {
            put("session_id", "stored-w")
            put("profile", "work")
        }, result = """{"session_id":"rt-w"}""")

        val submit = call(client, transport, "prompt.submit", buildJsonObject {
            put("session_id", "rt-w")
            put("text", "hi")
        })

        assertNull(submit["profile"])
    }

    /** A new chat's `session.create` as [dev.hermeskotlin.core.chat.ChatSession] makes it. */
    private fun kylleneCreate() = buildJsonObject {
        put("profile", "work")
        put("source", "desktop")
        put("cols", 80)
        put("cwd", "/home/me/project")
        put("model", "test-model")
        put("provider", "custom")
        put("reasoning_effort", "high")
        put("fast", true)
    }

    private companion object {
        val AUTHORITY_READY =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}"""

        /** tui_gateway/contracts/canonical_sessions.py `CanonicalCreateParams` (with ProfileParams' `profile`). */
        val CANONICAL_CREATE_KEYS = setOf(
            "profile", "request_id", "source", "cwd", "row_cwd", "model", "provider", "base_url", "api_key", "toolsets",
            "reasoning", "max_turns", "ignore_rules", "yolo", "safe_mode", "ignore_user_config", "skills", "checkpoints",
            "accept_hooks", "pass_session_id", "editor", "title", "hidden", "follow_profile_config", "service_tier",
            "cwd_explicit", "run_budget", "verbose", "client_env", "client",
        )
    }
}

package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BotsApiTest {

    /** Every `profiles.configure` the gateway was sent. */
    private val configured = mutableListOf<JsonObject>()

    /** Answers every call's params with [answer]. */
    private suspend fun api(scope: CoroutineScope, answer: (JsonObject) -> String): BotsApi {
        val gateway = FakeGateway(scope, reconnects = false)
        gateway.answer = { call ->
            if (call.method == "profiles.configure") configured += call.params
            answer(call.params)
        }
        return BotsApi(gateway.start())
    }

    @Test
    fun aModelIsSetOnTheBotsProfile() = runTest {
        val api = api(backgroundScope) { """{"ok":true,"applied":{"model":true}}""" }

        assertNull(api.setModel("scribe", "claude-sonnet-5-5", "anthropic"))

        val sent = configured.single()
        assertEquals("scribe", sent["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("claude-sonnet-5-5", sent["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("anthropic", sent["provider"]?.jsonPrimitive?.contentOrNull)
        assertNull(sent["confirm_expensive_model"])
    }

    @Test
    fun aGuardedModelIsAskedAboutAndSentAgainOnceConfirmed() = runTest {
        val api = api(backgroundScope) { params ->
            if (params["confirm_expensive_model"] == null) {
                """{"ok":true,"applied":{},"confirm_required":true,"confirm_message":"This model costs $15 per million tokens."}"""
            } else {
                """{"ok":true,"applied":{"model":true}}"""
            }
        }

        assertEquals("This model costs $15 per million tokens.", api.setModel("scribe", "big", "openrouter"))
        assertNull(api.setModel("scribe", "big", "openrouter", confirmed = true))

        assertEquals("true", configured.last()["confirm_expensive_model"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aModelTheGatewayDidntSaveIsAnError() = runTest {
        val api = api(backgroundScope) { """{"ok":false,"applied":{"model":false}}""" }

        assertFailsWith<RpcException> { api.setModel("scribe", "x", "y") }
    }

    @Test
    fun theAssistantsToolsetsArePinnedToExactlyThoseOn() = runTest {
        val api = api(backgroundScope) { """{"ok":true,"applied":{"toolsets":true}}""" }

        api.pinToolsets(ASSISTANT_PROFILE, ASSISTANT_TOOLSETS.toSet())

        val sent = configured.single()
        assertEquals(ASSISTANT_PROFILE, sent["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(ASSISTANT_TOOLSETS.sorted(), sent["enabled_toolsets"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(sent["model"])
    }

    @Test
    fun toolsetsTheGatewayDidntSaveAreAnError() = runTest {
        val api = api(backgroundScope) { """{"ok":false,"applied":{"toolsets":false}}""" }

        assertFailsWith<RpcException> { api.pinToolsets(ASSISTANT_PROFILE, setOf("web")) }
    }

    @Test
    fun anAssistantWithNoToolsetsIsRefusedBeforeAnythingIsSent() = runTest {
        val api = api(backgroundScope) { """{"ok":true,"applied":{"toolsets":true}}""" }
        val rolledBack = mutableListOf<String>()

        assertFailsWith<IllegalArgumentException> {
            api.createAssistant(ASSISTANT_PROFILE, emptySet()) { rolledBack += it }
        }

        // Nothing went out: no profile created, no empty pin sent, and nothing to roll back.
        assertEquals(emptyList<JsonObject>(), configured)
        assertEquals(emptyList<String>(), rolledBack)
    }

    @Test
    fun anEmptyPinIsRefusedBeforeAnythingIsSent() = runTest {
        val api = api(backgroundScope) { """{"ok":true,"applied":{"toolsets":true}}""" }

        assertFailsWith<IllegalArgumentException> { api.pinToolsets(ASSISTANT_PROFILE, emptySet()) }

        assertEquals(emptyList<JsonObject>(), configured)
    }

    @Test
    fun aPinTheGatewayDidntConfirmIsAnError() = runTest {
        val api = api(backgroundScope) { """{"ok":true}""" }

        assertFailsWith<RpcException> { api.pinToolsets(ASSISTANT_PROFILE, setOf("web")) }
    }

    @Test
    fun aBotMadeButNotPinnedIsRolledBack() = runTest {
        val gateway = FakeGateway(backgroundScope, reconnects = false)
        gateway.answer = { call ->
            when (call.method) {
                "profiles.list" -> """{"profiles":[{"name":"assistant"}]}"""
                // The pin the setup ends with is the one the gateway refuses.
                "profiles.configure" -> """{"ok":true,"applied":{"toolsets":false}}"""
                else -> """{"ok":true}"""
            }
        }
        val api = BotsApi(gateway.start())
        val rolledBack = mutableListOf<String>()

        assertFailsWith<RpcException> {
            api.createAssistant(ASSISTANT_PROFILE, ASSISTANT_TOOLSETS.toSet()) { rolledBack += it }
        }

        assertEquals(listOf(ASSISTANT_PROFILE), rolledBack)
    }

    @Test
    fun aBotMadeWithAnUnconfirmedPinIsRolledBack() = runTest {
        val gateway = FakeGateway(backgroundScope, reconnects = false)
        gateway.answer = { call ->
            when (call.method) {
                "profiles.list" -> """{"profiles":[{"name":"assistant"}]}"""
                // The pin's reply says nothing about toolsets: that is not a save.
                "profiles.configure" -> """{"ok":true}"""
                else -> """{"ok":true}"""
            }
        }
        val api = BotsApi(gateway.start())
        val rolledBack = mutableListOf<String>()

        assertFailsWith<RpcException> {
            api.createAssistant(ASSISTANT_PROFILE, ASSISTANT_TOOLSETS.toSet()) { rolledBack += it }
        }

        assertEquals(listOf(ASSISTANT_PROFILE), rolledBack)
    }

    @Test
    fun aBotMadeAndPinnedIsNotRolledBack() = runTest {
        val gateway = FakeGateway(backgroundScope, reconnects = false)
        gateway.answer = { call ->
            when (call.method) {
                "profiles.list" -> """{"profiles":[{"name":"assistant"}]}"""
                else -> """{"ok":true,"applied":{"toolsets":true}}"""
            }
        }
        val api = BotsApi(gateway.start())
        val rolledBack = mutableListOf<String>()

        api.createAssistant(ASSISTANT_PROFILE, ASSISTANT_TOOLSETS.toSet()) { rolledBack += it }

        assertEquals(emptyList<String>(), rolledBack)
    }

    @Test
    fun anAssistantCreateDroppedOnceIsRetriedTransparently() = runTest {
        val gateway = FakeGateway(backgroundScope)
        gateway.ready = { FakeGateway.QUIET_READY }
        val created = mutableListOf<String>()
        val rolledBack = mutableListOf<String>()
        gateway.answer = { call ->
            when (call.method) {
                "profiles.create" -> {
                    val name = call.param("name").orEmpty()
                    if (created.isEmpty()) {
                        // The gateway got the create; the socket dies before its reply reaches the phone.
                        created += name
                        call.socket.serverClose(null, "Connection reset")
                        null
                    } else {
                        """{"ok":true}"""
                    }
                }
                "profiles.list" -> """{"profiles":[{"name":"assistant"}]}"""
                "profiles.configure" -> """{"ok":true,"applied":{"toolsets":true}}"""
                else -> """{"ok":true}"""
            }
        }
        val api = BotsApi(gateway.start())

        val made = api.createAssistant(ASSISTANT_PROFILE, ASSISTANT_TOOLSETS.toSet()) { rolledBack += it }

        assertEquals(BotCreated(ASSISTANT_PROFILE, readyToChat = true, problem = null), made)
        assertEquals(2, gateway.sent("profiles.create").size)
        assertEquals(emptyList<String>(), rolledBack)
    }

    @Test
    fun anAssistantCreatedBeforeTheDropIsAdoptedNotFailed() = runTest {
        val gateway = FakeGateway(backgroundScope)
        gateway.ready = { FakeGateway.QUIET_READY }
        val existing = mutableSetOf<String>()
        val rolledBack = mutableListOf<String>()
        gateway.answer = { call ->
            when (call.method) {
                "profiles.create" -> {
                    val name = call.param("name").orEmpty()
                    if (existing.add(name)) {
                        // Created server-side, then the socket drops: the retry sees "already exists".
                        call.socket.serverClose(null, "Connection reset")
                        null
                    } else {
                        call.error(4062, "Profile already exists")
                    }
                }
                "profiles.list" -> """{"profiles":[{"name":"assistant"}]}"""
                "profiles.configure" -> """{"ok":true,"applied":{"toolsets":true}}"""
                else -> """{"ok":true}"""
            }
        }
        val api = BotsApi(gateway.start())

        val made = api.createAssistant(ASSISTANT_PROFILE, ASSISTANT_TOOLSETS.toSet()) { rolledBack += it }

        // The half-made profile is adopted, not failed: one create tried again, the pin landed, no rollback.
        assertEquals(2, gateway.sent("profiles.create").size)
        assertEquals(1, gateway.sent("profiles.configure").size)
        assertEquals(BotCreated(ASSISTANT_PROFILE, readyToChat = true, problem = null), made)
        assertEquals(emptyList<String>(), rolledBack)
    }
}

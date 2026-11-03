package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BotsApiTest {

    /** Every `profiles.configure` the gateway was sent. */
    private val configured = mutableListOf<JsonObject>()

    /** Every `profiles.set_asset` the gateway was sent. */
    private val assets = mutableListOf<JsonObject>()

    /** Answers every call's params with [answer]. */
    private suspend fun api(scope: CoroutineScope, answer: (JsonObject) -> String): BotsApi {
        val gateway = FakeGateway(scope, reconnects = false)
        gateway.answer = { call ->
            if (call.method == "profiles.configure") configured += call.params
            if (call.method == "profiles.set_asset") assets += call.params
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
    fun aPictureGoesUpAsTheBotsAvatarInADataUrl() = runTest {
        val api = api(backgroundScope) { """{"ok":true,"asset":"avatar","size":4}""" }

        api.setAvatar("scribe", byteArrayOf(1, 2, 3, 4))

        val sent = assets.single()
        assertEquals("scribe", sent["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("avatar", sent["asset"]?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/png;base64,AQIDBA==", sent["data"]?.jsonPrimitive?.contentOrNull)
        assertNull(sent["clear"])
    }

    @Test
    fun takingThePictureAwayClearsTheAsset() = runTest {
        val api = api(backgroundScope) { """{"ok":true,"asset":"avatar","size":0,"removed":true}""" }

        api.setAvatar("scribe", null)

        val sent = assets.single()
        assertEquals("true", sent["clear"]?.jsonPrimitive?.contentOrNull)
        assertNull(sent["data"])
    }

    @Test
    fun aPictureTheGatewayDidntKeepIsAnError() = runTest {
        val api = api(backgroundScope) { """{"ok":false}""" }

        assertFailsWith<RpcException> { api.setAvatar("scribe", byteArrayOf(1)) }
    }
}

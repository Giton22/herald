package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BotsApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** Every `profiles.configure` the gateway was sent. */
    private val configured = mutableListOf<JsonObject>()

    private fun CoroutineScope.serve(transport: FakeTransport, answer: (JsonObject) -> String) = launch {
        var answered = 0
        transport.sent.collect { sent ->
            sent.drop(answered).forEach { message ->
                answered++
                val id = message["id"] ?: return@forEach
                val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
                if (message["method"]?.jsonPrimitive?.contentOrNull == "profiles.configure") configured += params
                transport.push("""{"jsonrpc":"2.0","id":$id,"result":${answer(params)}}""")
            }
        }
    }

    private suspend fun api(scope: CoroutineScope, answer: (JsonObject) -> String): BotsApi {
        val http = createHttpClient(MockEngine { respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json) })
        val transport = FakeTransport()
        val connection = GatewayConnection(AuthApi(http, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> transport }, scope)
        scope.serve(transport, answer)
        connection.start(url)
        transport.push(FakeTransport.READY)
        connection.state.first { it is ConnectionState.Connected }
        return BotsApi(connection)
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
}

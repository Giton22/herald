package dev.hermeskotlin.core.models

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.rpc.param
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ModelsApiTest {

    /** OpenRouter without prices, as a normal `model.options` answers from a cold gateway cache. */
    private val bare = """{"providers":[{"slug":"openrouter","name":"OpenRouter","models":["a/m1","a/m2"]}]}"""

    /** The same with prices, as `refresh: true` answers. */
    private val priced = """{"providers":[{"slug":"openrouter","name":"OpenRouter","models":["a/m1","a/m2"],
        "pricing":{"a/m1":{"input":"${'$'}1","output":"${'$'}2","free":false}}}]}"""

    private fun FakeGateway.refreshes() = sent("model.options").count { it.param("refresh") == "true" }

    @Test
    fun aRefreshBringsThePricesOncePerConnection() = runTest {
        val gateway = FakeGateway(backgroundScope)
        gateway.answer = { call -> if (call.param("refresh") == "true") priced else bare }
        val api = ModelsApi(gateway.start())

        val catalog = api.options()
        assertNull(catalog.find("openrouter", "a/m1")!!.price)
        assertEquals(mapOf("openrouter/a/m1" to "$1 / $2"), api.missingPrices(catalog))
        assertEquals(1, gateway.refreshes())

        // Later loads get the prices without asking again.
        val again = api.options()
        assertEquals("$1 / $2", again.find("openrouter", "a/m1")!!.price)
        assertNull(api.missingPrices(api.options().copy(providers = catalog.providers)))
        assertEquals(1, gateway.refreshes())
    }

    @Test
    fun aCatalogWithPricesAsksNothing() = runTest {
        val gateway = FakeGateway(backgroundScope)
        gateway.answer = { priced }
        val api = ModelsApi(gateway.start())
        assertNull(api.missingPrices(api.options()))
        assertEquals(0, gateway.refreshes())
    }

    @Test
    fun aFailedRefreshIsntRepeatedOnTheSameConnection() = runTest {
        val gateway = FakeGateway(backgroundScope)
        gateway.answer = { call -> if (call.param("refresh") == "true") "error:5033" else bare }
        val api = ModelsApi(gateway.start())
        val catalog = api.options()
        assertFailsWith<RpcException> { api.missingPrices(catalog) }
        assertNull(api.missingPrices(catalog))
        assertEquals(1, gateway.refreshes())
    }

    @Test
    fun eachProfileIsAskedOnItsOwn() = runTest {
        val gateway = FakeGateway(backgroundScope)
        gateway.answer = { call -> if (call.param("refresh") == "true") priced else bare }
        val api = ModelsApi(gateway.start())
        api.missingPrices(api.options())
        api.missingPrices(api.options(profile = "work"), profile = "work")
        assertEquals(2, gateway.refreshes())
        assertEquals("work", gateway.sent("model.options").last().param("profile"))
    }

    @Test
    fun aNewConnectionAsksAgain() = runTest {
        val gateway = FakeGateway(backgroundScope)
        gateway.answer = { call -> if (call.param("refresh") == "true") priced else bare }
        val connection = gateway.start()
        val api = ModelsApi(connection)
        api.missingPrices(api.options())

        val before = (connection.state.value as ConnectionState.Connected).client
        gateway.socket.serverClose(1006)
        connection.state.first { it is ConnectionState.Connected && it.client !== before }

        // The old connection's prices are gone with it: the new one is asked afresh.
        assertNull(api.options().find("openrouter", "a/m1")!!.price)
        assertEquals(mapOf("openrouter/a/m1" to "$1 / $2"), api.missingPrices(api.options()))
        assertEquals(2, gateway.refreshes())
    }
}

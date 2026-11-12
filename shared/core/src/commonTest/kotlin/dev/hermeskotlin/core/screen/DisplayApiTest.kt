package dev.hermeskotlin.core.screen

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.param
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayApiTest {

    private suspend fun gateway(backgroundScope: kotlinx.coroutines.CoroutineScope, answer: (FakeGateway.Call) -> String?) =
        FakeGateway(backgroundScope, reconnects = false).also {
            it.answer = answer
            it.start()
        }

    @Test
    fun theStatusIsReadForTheProfile() = runTest {
        val gateway = gateway(backgroundScope) { call ->
            when (call.method) {
                "display.status" ->
                    """{"profile":"scribe","supported":true,"installed":true,"running":true,
                       "geometry":"1440x900","lease":{"holder":"agent","epoch":3},"profile_key":"scribe"}"""
                else -> "{}"
            }
        }

        val status = DisplayApi(gateway.connection).status("scribe")

        assertEquals("scribe", status?.profile)
        assertTrue(status?.supported == true)
        assertTrue(status?.running == true)
        assertEquals("1440x900", status?.geometry)
        assertEquals(DisplayLease.AGENT, status?.lease?.holder)
        assertEquals(3L, status?.lease?.epoch)
        assertEquals("scribe", gateway.sent("display.status").first().param("profile"))
    }

    @Test
    fun aGatewayWithoutTheScreenIsNotAnError() = runTest {
        val gateway = gateway(backgroundScope) { call -> call.error(-32601, "method not found") }

        assertNull(DisplayApi(gateway.connection).status("scribe"))
        assertNull(DisplayApi(gateway.connection).thumbnail("scribe"))
        assertNull(DisplayApi(gateway.connection).observe("scribe"))
    }

    @Test
    fun anotherErrorIsTheGatewaysOwn() = runTest {
        val gateway = gateway(backgroundScope) { call -> call.error(5300, "a human holds this screen") }

        val failure = runCatching { DisplayApi(gateway.connection).stop("scribe") }.exceptionOrNull()

        assertTrue(failure is dev.hermeskotlin.core.rpc.RpcException)
        assertEquals(5300, (failure as dev.hermeskotlin.core.rpc.RpcException).code)
    }

    @Test
    fun theTicketComesWithTheViewerIdAndTheRoute() = runTest {
        val gateway = gateway(backgroundScope) { call ->
            when (call.method) {
                "display.observe" ->
                    """{"ticket":"tk","path":"/api/display/ws","viewer_id":"v1",
                       "profile":"scribe","running":true,"lease":{"holder":"agent"}}"""
                else -> "{}"
            }
        }

        val ticket = DisplayApi(gateway.connection).observe("scribe")

        assertEquals("tk", ticket?.ticket)
        assertEquals("/api/display/ws", ticket?.path)
        assertEquals("v1", ticket?.viewerId)
        assertEquals("scribe", gateway.sent("display.observe").first().param("profile"))
    }

    @Test
    fun theSameViewerIdIsSentWhenThereIsOne() = runTest {
        val gateway = gateway(backgroundScope) { call ->
            when (call.method) {
                "display.observe" -> """{"ticket":"tk","path":"/api/display/ws","viewer_id":"v1"}"""
                else -> "{}"
            }
        }

        DisplayApi(gateway.connection).observe("scribe", "v0")

        assertEquals("v0", gateway.sent("display.observe").first().param("viewer_id"))
    }

    @Test
    fun takingOverAndHandingBackNameTheViewer() = runTest {
        val gateway = gateway(backgroundScope) { call ->
            when (call.method) {
                "display.lease.acquire" -> """{"lease":{"holder":"human","viewer_hash":"ab","epoch":4}}"""
                "display.lease.release" -> """{"lease":{"holder":"agent","epoch":5}}"""
                else -> "{}"
            }
        }
        val api = DisplayApi(gateway.connection)

        val taken = api.acquire("scribe", "v1")
        assertEquals(DisplayLease.HUMAN, taken?.holder)
        assertEquals("v1", gateway.sent("display.lease.acquire").first().param("viewer_id"))

        val handed = api.release("scribe", "v1")
        assertEquals(DisplayLease.AGENT, handed?.holder)
        assertEquals("v1", gateway.sent("display.lease.release").first().param("viewer_id"))
    }

    @Test
    fun theThumbnailIsTheGatewaysOwnPicture() = runTest {
        val gateway = gateway(backgroundScope) { call ->
            when (call.method) {
                "display.thumbnail" -> """{"data_url":"data:image/jpeg;base64,AAA"}"""
                else -> "{}"
            }
        }

        assertEquals("data:image/jpeg;base64,AAA", DisplayApi(gateway.connection).thumbnail("scribe"))
    }

    @Test
    fun aThumbnailIsNothingWhileAHumanHoldsTheScreen() = runTest {
        val gateway = gateway(backgroundScope) { call ->
            when (call.method) {
                "display.thumbnail" -> """{"data_url":null,"suppressed":"human_has_control"}"""
                else -> "{}"
            }
        }

        assertNull(DisplayApi(gateway.connection).thumbnail("scribe"))
    }

    @Test
    fun theConnectionIsNeededForACall() = runTest {
        val gateway = FakeGateway(backgroundScope, reconnects = false)
        val api = DisplayApi(gateway.connection)

        val failure = runCatching { api.status("scribe") }.exceptionOrNull()

        // Not the gateway's own answer: the call never got there, which is a different thing.
        assertTrue(failure is GatewayUnreachable)
    }

    @Test
    fun theClientComesFromTheConnectedGateway() = runTest {
        val gateway = gateway(backgroundScope) { "{}" }
        val state = gateway.connection.state.value

        assertTrue(state is ConnectionState.Connected)
    }
}

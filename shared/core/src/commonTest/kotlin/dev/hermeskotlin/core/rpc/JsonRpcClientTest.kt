package dev.hermeskotlin.core.rpc

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonRpcClientTest {

    private fun JsonPrimitive.str() = content

    @Test
    fun readyAdvertisesServerRequestCapability() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport)
        val pump = launch { runCatching { client.run() } }

        transport.push(FakeTransport.READY)
        val ready = client.ready.await()
        assertEquals("e1", ready["replay_epoch"]!!.jsonPrimitive.str())

        val caps = transport.awaitSent { it["method"]?.jsonPrimitive?.str() == "client.capabilities" }
        assertEquals("true", caps["params"]!!.jsonObject["server_requests"]!!.jsonPrimitive.str())
        pump.cancel()
    }

    @Test
    fun requestResolvesWithMatchingResult() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport)
        val pump = launch { runCatching { client.run() } }

        val call = async { client.request("session.list", buildJsonObject { put("limit", 5) }) }
        val sent = transport.awaitSent { it["method"]?.jsonPrimitive?.str() == "session.list" }
        val id = sent["id"]!!.jsonPrimitive.str()
        transport.push("""{"jsonrpc":"2.0","id":"$id","result":{"sessions":[]}}""")

        assertEquals("{\"sessions\":[]}", call.await().toString())
        pump.cancel()
    }

    @Test
    fun rpcErrorBecomesException() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport)
        val pump = launch { runCatching { client.run() } }

        val call = async { runCatching { client.request("nope") } }
        val id = transport.awaitSent { it["method"]?.jsonPrimitive?.str() == "nope" }["id"]!!.jsonPrimitive.str()
        transport.push("""{"jsonrpc":"2.0","id":"$id","error":{"code":-32601,"message":"unknown method"}}""")

        val error = call.await().exceptionOrNull() as RpcException
        assertEquals(-32601, error.code)
        pump.cancel()
    }

    @Test
    fun serverRequestIsEmittedAndAnsweredById() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport)
        val pump = launch { runCatching { client.run() } }
        val incoming = async(start = CoroutineStart.UNDISPATCHED) { client.serverRequests.first() }

        transport.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"s1","command":"rm -rf /tmp/x"}}""")
        val request = incoming.await()
        assertEquals("approval", request.method)
        assertEquals("s1", request.sessionId)

        client.respond(request.id, buildJsonObject { put("choice", "once") })
        val reply = transport.awaitSent { it["id"]?.jsonPrimitive?.str() == "srq-1" }
        assertEquals("once", reply["result"]!!.jsonObject["choice"]!!.jsonPrimitive.str())
        pump.cancel()
    }

    @Test
    fun unhandledServerRequestIsLeftForOtherClients() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport)
        val pump = launch { runCatching { client.run() } }

        transport.push("""{"jsonrpc":"2.0","id":"srq-2","method":"preview.read","params":{"session_id":"s1"}}""")
        // A ping round-trip proves the frame was processed without any reply to srq-2.
        val call = async { client.request("gateway.ping") }
        val id = transport.awaitSent { it["method"]?.jsonPrimitive?.str() == "gateway.ping" }["id"]!!.jsonPrimitive.str()
        transport.push("""{"jsonrpc":"2.0","id":"$id","result":{}}""")
        call.await()
        assertTrue(transport.sent.value.none { it["id"]?.jsonPrimitive?.str() == "srq-2" })
        pump.cancel()
    }

    @Test
    fun eventsAreParsed() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport)
        val pump = launch { runCatching { client.run() } }
        val event = async(start = CoroutineStart.UNDISPATCHED) { client.events.first { it.type == "message.delta" } }

        transport.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s1","seq":7,"payload":{"text":"hi"}}}""")

        val e = event.await()
        assertEquals("s1", e.sessionId)
        assertEquals(7L, e.seq)
        pump.cancel()
    }

    @Test
    fun closeCodeSurfacesAndFailsPendingCalls() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport)
        val run = async { runCatching { client.run() }.exceptionOrNull() }
        val call = async { runCatching { client.request("slow") }.exceptionOrNull() }
        transport.awaitSent { it["method"]?.jsonPrimitive?.str() == "slow" }

        transport.serverClose(4401, "ticket")

        val closed = run.await() as TransportClosedException
        assertEquals(GatewayCloseCodes.TICKET_REJECTED, closed.code)
        assertTrue(call.await() is TransportClosedException)
        assertFailsWith<TransportClosedException> { client.ready.await() }
    }
}

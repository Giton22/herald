package dev.hermeskotlin.core.rpc

import dev.hermeskotlin.core.chat.InputRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
    fun aCallNobodyAnswersFailsWithATimeoutNotACancellation() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport)
        val pump = launch { runCatching { client.run() } }

        val error = assertFailsWith<RpcTimeoutException> { client.request("prompt.submit", timeoutMs = 1_000) }

        assertEquals("prompt.submit", error.method)
        pump.cancel()
    }

    @Test
    fun aHeartbeatTimeoutFailsWaitingCallsAsALostLinkNotACancellation() = runTest {
        val transport = FakeTransport(dead = true)
        val client = JsonRpcClient(transport, clock = { testScheduler.currentTime })
        val pump = launch { runCatching { client.run() } }
        transport.push(FakeTransport.READY)
        client.ready.await()

        // Nothing comes in any more, so the heartbeat gives up long before this call would time out.
        val call = async { runCatching { client.request("slash.exec", timeoutMs = 600_000) }.exceptionOrNull() }
        transport.awaitSent { it["method"]?.jsonPrimitive?.str() == "slash.exec" }

        val error = call.await()
        assertTrue(error != null && error !is CancellationException, "failed with $error")
        // And the dead client turns new calls away at once, the same way.
        val next = runCatching { client.request("command.dispatch", timeoutMs = 600_000) }.exceptionOrNull()
        assertEquals(error, next)
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
    fun aServerRequestNothingHereCanShowIsLeftForDesktop() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport, answers = { it == "approval" })
        val pump = launch { runCatching { client.run() } }
        val incoming = async(start = CoroutineStart.UNDISPATCHED) { client.serverRequests.first() }

        transport.push("""{"jsonrpc":"2.0","id":"srq-3","method":"tour","params":{"session_id":"s1"}}""")
        transport.push("""{"jsonrpc":"2.0","id":"srq-4","method":"approval","params":{"session_id":"s1","command":"ls"}}""")

        // One it can show reaches the app, unanswered until a person decides.
        assertEquals("srq-4", incoming.await().id)
        // No reply to the tour: any error settles it for every client, and Desktop can show it.
        val call = async { client.request("gateway.ping") }
        val id = transport.awaitSent { it["method"]?.jsonPrimitive?.str() == "gateway.ping" }["id"]!!.jsonPrimitive.str()
        transport.push("""{"jsonrpc":"2.0","id":"$id","result":{}}""")
        call.await()
        assertTrue(transport.sent.value.none { it["id"]?.jsonPrimitive?.str() in setOf("srq-3", "srq-4") })
        pump.cancel()
    }

    @Test
    fun vaultPromptsReachTheApp() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport, answers = InputRequest.METHODS::contains)
        val pump = launch { runCatching { client.run() } }
        val incoming = async(start = CoroutineStart.UNDISPATCHED) { client.serverRequests.take(3).toList() }

        transport.push("""{"jsonrpc":"2.0","id":"srq-7","method":"vault.unlock_prompt","params":{"session_id":"s1","backend":"bitwarden"}}""")
        transport.push("""{"jsonrpc":"2.0","id":"srq-8","method":"vault.code","params":{"session_id":"s1","site":"github.com"}}""")
        transport.push("""{"jsonrpc":"2.0","id":"srq-9","method":"vault.save_login","params":{"session_id":"s1","origin":"https://github.com"}}""")

        assertEquals(listOf("vault.unlock_prompt", "vault.code", "vault.save_login"), incoming.await().map { it.method })
        pump.cancel()
    }

    @Test
    fun whatNothingHereCanShowIsDeclinedWhenTheGatewayCountsDeclines() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport, answers = { it == "approval" })
        val pump = launch { runCatching { client.run() } }
        transport.push(FakeTransport.READY)
        val caps = transport.awaitSent { it["method"]?.jsonPrimitive?.str() == "client.capabilities" }
        transport.push("""{"jsonrpc":"2.0","id":"${caps["id"]!!.jsonPrimitive.str()}","result":{"declines_not_shown":true}}""")
        // Settle the capabilities reply before the request lands.
        val ping = async { client.request("gateway.ping") }
        val pingId = transport.awaitSent { it["method"]?.jsonPrimitive?.str() == "gateway.ping" }["id"]!!.jsonPrimitive.str()
        transport.push("""{"jsonrpc":"2.0","id":"$pingId","result":{}}""")
        ping.await()

        transport.push("""{"jsonrpc":"2.0","id":"srq-5","method":"preview.read","params":{"session_id":"s1"}}""")
        transport.push("""{"jsonrpc":"2.0","id":"srq-6","method":"tour","params":{"session_id":"s1"}}""")

        // Declines, which leave them to Desktop (the window showing the chat, or its tour); -32601
        // would settle them for Desktop too.
        for (id in listOf("srq-5", "srq-6")) {
            val decline = transport.awaitSent { it["id"]?.jsonPrimitive?.str() == id }
            assertEquals("4404", decline["error"]!!.jsonObject["code"]!!.jsonPrimitive.str())
        }
        pump.cancel()
    }

    @Test
    fun aWindowOwnedRequestIsLeftAloneWhenTheGatewayDoesNotCountDeclines() = runTest {
        val transport = FakeTransport()
        val client = JsonRpcClient(transport, answers = { it == "approval" })
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

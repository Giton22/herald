package dev.hermeskotlin.core.connection

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.rpc.GatewayCloseCodes
import dev.hermeskotlin.core.rpc.HandshakeRejectedException
import dev.hermeskotlin.core.rpc.handshakeStatus
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GatewayConnectionTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun auth(status: HttpStatusCode = HttpStatusCode.OK): AuthApi {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val engine = MockEngine { respond("""{"ticket":"T","ttl_seconds":30}""", status, json) }
        return AuthApi(createHttpClient(engine, cookies), cookies)
    }

    @Test
    fun connectsWithTicketAndPublishesReady() = runTest {
        val transport = FakeTransport()
        var usedTicket: String? = null
        val connection = GatewayConnection(auth(), { _, ticket -> usedTicket = ticket; transport }, backgroundScope)

        connection.start(url)
        transport.push(FakeTransport.READY)

        val connected = connection.state.first { it is ConnectionState.Connected } as ConnectionState.Connected
        assertEquals("T", usedTicket)
        assertEquals("e1", connected.ready["replay_epoch"].toString().trim('"'))
        connection.stop()
    }

    @Test
    fun expiredSessionStopsWithSessionExpired() = runTest {
        val connection = GatewayConnection(auth(HttpStatusCode.Unauthorized), { _, _ -> error("must not open") }, backgroundScope)
        connection.start(url)
        assertIs<ConnectionState.SessionExpired>(connection.state.first { it is ConnectionState.SessionExpired })
    }

    @Test
    fun hostGuardRejectionIsTerminal() = runTest {
        val connection = GatewayConnection(
            auth(),
            { _, _ -> FakeTransport().also { it.serverClose(GatewayCloseCodes.REQUEST_GUARD, "host_mismatch") } },
            backgroundScope,
        )
        connection.start(url)
        assertIs<ConnectionState.Failed>(connection.state.first { it is ConnectionState.Failed })
    }

    @Test
    fun repeatedHandshake403BecomesFailedAfterFreshTicketRetries() = runTest {
        var attempts = 0
        val connection = GatewayConnection(
            auth(),
            { _, _ ->
                attempts++
                throw HandshakeRejectedException(403, IllegalStateException("Expected HTTP 101 response but was '403 Forbidden'"))
            },
            backgroundScope,
        )
        connection.start(url)
        assertIs<ConnectionState.Failed>(connection.state.first { it is ConnectionState.Failed })
        assertEquals(3, attempts)
    }

    @Test
    fun handshakeStatusIsParsedFromOkHttpMessage() {
        assertEquals(403, handshakeStatus("Expected HTTP 101 response but was '403 Forbidden'"))
        assertEquals(null, handshakeStatus("Connection reset"))
    }

    @Test
    fun droppedConnectionSchedulesReconnect() = runTest {
        val first = FakeTransport()
        val transports = ArrayDeque(listOf(first, FakeTransport()))
        val connection = GatewayConnection(auth(), { _, _ -> transports.removeFirst() }, backgroundScope)
        connection.start(url)
        first.push(FakeTransport.READY)
        connection.state.first { it is ConnectionState.Connected }

        first.serverClose(1006, "abnormal")

        assertIs<ConnectionState.Reconnecting>(connection.state.first { it is ConnectionState.Reconnecting })
        connection.stop()
    }

    @Test
    fun readyTimeoutSchedulesReconnect() = runTest {
        // The socket connects but gateway.ready never arrives: that attempt must fail like any other
        // and retry with a fresh ticket, not end the loop silently as a cancellation.
        var opens = 0
        val connection = GatewayConnection(auth(), { _, _ -> opens++; FakeTransport() }, backgroundScope, readyTimeoutMs = 50)
        connection.start(url)

        val second = connection.state.first { it is ConnectionState.Reconnecting && it.attempt >= 2 }

        assertEquals(2, opens)
        connection.stop()
    }
}

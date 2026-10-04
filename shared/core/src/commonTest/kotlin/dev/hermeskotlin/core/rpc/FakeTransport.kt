package dev.hermeskotlin.core.rpc

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * In-memory [RpcTransport]: tests push server frames and inspect what the client sent. With [dead], closing
 * from this end does nothing, like a socket whose network is gone.
 */
class FakeTransport(private val dead: Boolean = false) : RpcTransport {
    private val inbound = Channel<String>(Channel.UNLIMITED)
    private var closeWith: TransportClosedException? = null
    val sent = MutableStateFlow<List<JsonObject>>(emptyList())

    override val incoming: Flow<String> = flow {
        for (text in inbound) emit(text)
        throw closeWith ?: TransportClosedException(null, "closed")
    }

    override suspend fun send(text: String) {
        sent.value = sent.value + HermesJson.parseToJsonElement(text).jsonObject
    }

    override suspend fun close(code: Short, reason: String) {
        if (!dead) serverClose(code, reason)
    }

    fun push(json: String) {
        inbound.trySend(json)
    }

    fun serverClose(code: Short?, reason: String? = null) {
        closeWith = TransportClosedException(code, reason)
        inbound.close()
    }

    suspend fun awaitSent(predicate: (JsonObject) -> Boolean): JsonObject =
        sent.first { list -> list.any(predicate) }.first(predicate)

    companion object {
        const val READY = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":true,"replay_epoch":"e1"}}}"""
    }
}

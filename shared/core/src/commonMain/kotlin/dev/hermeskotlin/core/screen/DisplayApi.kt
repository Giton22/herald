package dev.hermeskotlin.core.screen

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The app can't reach the gateway, so a call never got there.
 *
 * It is not the gateway's answer, so it must not be worded as one: the screen may be perfectly fine and the
 * link may simply be down. [DisplayApi] raises this instead of a plain [RpcException] so that a caller can
 * tell "the gateway said no" from "the gateway never heard me".
 */
class GatewayUnreachable(override val message: String) : Exception(message)

/**
 * The gateway's Bot Screen calls. Every one of them answers for the profile it is given, so one connection
 * can serve several bots.
 *
 * A gateway older than the Bot Screen has no `display.*` method at all and answers "method not found". That
 * is not an error here: the calls return null, so the app can say the gateway has no screen instead of
 * failing. Any other error is the gateway's own answer about the screen (it refuses to stop a screen a
 * human holds, for one) and travels on as it is. A call that never reached the gateway at all is neither of
 * those: it raises [GatewayUnreachable].
 */
class DisplayApi(private val connection: GatewayConnection) {

    /** The screen's state: whether it can run, whether it runs, and who holds it. */
    suspend fun status(profile: String): DisplayStatus? =
        statusReply(request("display.status", profileParams(profile)))

    /** One JPEG grab of the screen, or null while the screen is stopped or a human holds it. */
    suspend fun thumbnail(profile: String): String? {
        val reply = request("display.thumbnail", profileParams(profile)) as? JsonObject ?: return null
        return (reply["data_url"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }

    /** Starts the screen (it is idempotent) and waits until the display is published. */
    suspend fun start(profile: String): DisplayStatus? =
        statusReply(request("display.start", profileParams(profile)))

    /** Stops the screen. Refused while a human holds it, unless [force]. */
    suspend fun stop(profile: String, force: Boolean = false): DisplayStatus? {
        val params = buildJsonObject {
            put("profile", profile)
            put("force", force)
        }
        return statusReply(request("display.stop", params))
    }

    /**
     * Mints a ticket for the display stream and the viewer id that goes with it. The ticket is single-use
     * and short-lived, so it is taken right before the stream opens. Passing the [viewerId] of an earlier
     * call keeps the same identity, which is what lets a client that lost the link come back to the lease
     * it still holds.
     */
    suspend fun observe(profile: String, viewerId: String? = null): DisplayTicket? {
        val params = buildJsonObject {
            put("profile", profile)
            if (viewerId != null) put("viewer_id", viewerId)
        }
        val reply = request("display.observe", params) as? JsonObject ?: return null
        val ticket = (reply["ticket"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val path = (reply["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: DISPLAY_PATH
        val viewer = (reply["viewer_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return DisplayTicket(ticket, path, viewer, reply.toDisplayStatus(profile))
    }

    /** Takes over: the human named by [viewerId] drives the screen until it hands back. */
    suspend fun acquire(profile: String, viewerId: String): DisplayLease? {
        val params = buildJsonObject {
            put("profile", profile)
            put("viewer_id", viewerId)
        }
        return leaseReply(request("display.lease.acquire", params))
    }

    /**
     * Hands back. Without a [viewerId] the gateway refuses while a human holds, unless [force] says the
     * caller means it.
     */
    suspend fun release(profile: String, viewerId: String? = null, force: Boolean = false): DisplayLease? {
        val params = buildJsonObject {
            put("profile", profile)
            if (viewerId != null) put("viewer_id", viewerId)
            if (force) put("force", true)
        }
        return leaseReply(request("display.lease.release", params))
    }

    private suspend fun request(method: String, params: JsonObject): JsonElement? = try {
        client().request(method, params)
    } catch (e: RpcException) {
        if (e.code == METHOD_NOT_FOUND) null else throw e
    }

    private fun client(): JsonRpcClient = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw GatewayUnreachable("The gateway isn't there right now.")

    private fun profileParams(profile: String): JsonObject = buildJsonObject { put("profile", profile) }

    private fun statusReply(reply: JsonElement?): DisplayStatus? =
        (reply as? JsonObject)?.toDisplayStatus("")

    private fun leaseReply(reply: JsonElement?): DisplayLease? =
        (reply as? JsonObject)?.let { (it["lease"] as? JsonObject)?.toDisplayLease() }

    companion object {
        /** The gateway's route for the display stream, next to the socket the app already talks on. */
        const val DISPLAY_PATH = "/api/display/ws"

        /** JSON-RPC's "no such method": the gateway is too old for the Bot Screen. */
        private const val METHOD_NOT_FOUND = -32601
    }
}

package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.gateway.GatewayUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Only a fresh, exact replay from the chosen profile can authorize opening a request call. */
fun verifyCallRequest(origin: CallOrigin, gateway: GatewayUrl, storedId: String?, callId: String?, request: InputRequest, reply: JsonObject?): Boolean {
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val method = when (request) {
        is InputRequest.Approval -> "approval"
        is InputRequest.Clarify -> "clarify"
        else -> return false
    }
    if (storedId.isNullOrBlank() || origin.gateway != gateway || origin.profile.isNullOrBlank() ||
        origin.runtimeId.isNullOrBlank() || callId != request.id || origin.requestId != request.id || origin.method != method ||
        reply?.text("session_id") != origin.runtimeId) return false
    val open = reply["open_requests"] as? JsonArray ?: return false
    val matches = open.mapNotNull { it as? JsonObject }.filter { it.text("id") == request.id }
    if (matches.size != 1) return false
    val entry = matches.single()
    val params = entry["params"] as? JsonObject ?: return false
    return entry.text("method") == method && params.text("session_id") == origin.runtimeId &&
        InputRequest.parse(request.id, method, params) == request
}

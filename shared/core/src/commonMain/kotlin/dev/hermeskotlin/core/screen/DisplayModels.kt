package dev.hermeskotlin.core.screen

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Who is driving a bot's screen: the agent, or a human watching it. The holder's viewer id is a capability
 * and never leaves the gateway; [viewerHash] is the part a client may see, so a client can recognise
 * itself as the holder without holding the id.
 */
data class DisplayLease(
    val holder: String,
    val viewerHash: String? = null,
    val since: Double = 0.0,
    val epoch: Long = 0,
    val reason: String = "",
) {
    val heldByHuman: Boolean get() = holder == HUMAN
    val heldByAgent: Boolean get() = holder == AGENT

    companion object {
        const val AGENT = "agent"
        const val HUMAN = "human"
    }
}

/**
 * The gateway's snapshot of one profile's screen: whether the host can run one, whether it runs now,
 * and who holds it. A field the gateway left out is absent here rather than guessed.
 */
data class DisplayStatus(
    val profile: String,
    val supported: Boolean = false,
    val installed: Boolean = false,
    val missing: List<String> = emptyList(),
    val running: Boolean = false,
    val display: String? = null,
    val socket: String? = null,
    val geometry: String = "",
    val installCommand: String? = null,
    val blocker: String? = null,
    val memoryAvailableMb: Int? = null,
    val memoryLimitMb: Int? = null,
    val placement: String = "gateway",
    val lease: DisplayLease = DisplayLease(DisplayLease.AGENT),
    val profileKey: String = "",
)

/** A ticket for the display stream, with the viewer id the gateway minted alongside it. */
data class DisplayTicket(
    val ticket: String,
    val path: String,
    val viewerId: String,
    val status: DisplayStatus,
)

internal fun JsonObject.toDisplayLease(): DisplayLease = DisplayLease(
    holder = string("holder") ?: DisplayLease.AGENT,
    viewerHash = string("viewer_hash"),
    since = double("since") ?: 0.0,
    epoch = long("epoch") ?: 0,
    reason = string("reason").orEmpty(),
)

internal fun JsonObject.toDisplayStatus(profile: String): DisplayStatus = DisplayStatus(
    profile = string("profile") ?: profile,
    supported = bool("supported") ?: false,
    installed = bool("installed") ?: false,
    missing = array("missing"),
    running = bool("running") ?: false,
    display = string("display"),
    socket = string("socket"),
    geometry = string("geometry").orEmpty(),
    installCommand = string("install_command"),
    blocker = string("blocker"),
    memoryAvailableMb = int("memory_available_mb"),
    memoryLimitMb = int("memory_limit_mb"),
    placement = string("placement") ?: "gateway",
    lease = (this["lease"] as? JsonObject)?.toDisplayLease() ?: DisplayLease(DisplayLease.AGENT),
    profileKey = string("profile_key").orEmpty(),
)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.array(key: String): List<String> =
    (this[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        .orEmpty()

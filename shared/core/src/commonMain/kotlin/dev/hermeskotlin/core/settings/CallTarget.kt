package dev.hermeskotlin.core.settings

import kotlinx.serialization.Serializable

/** A call destination: one bot’s permanent chat, a picked session, or a fresh selected-profile session. */
@Serializable
data class CallTarget(
    val profile: String? = null,
    val sessionId: String? = null,
    val label: String = "New session",
) {
    val valid: Boolean get() = profile?.isBlank() != true && sessionId?.isBlank() != true && (profile == null || sessionId == null)
}

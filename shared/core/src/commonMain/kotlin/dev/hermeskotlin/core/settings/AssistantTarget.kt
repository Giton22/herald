package dev.hermeskotlin.core.settings

import kotlinx.serialization.Serializable

/** A phone assistant destination owned by the account that picked it. Null ID means New chat. */
@Serializable
data class AssistantTarget(val userId: String, val sessionId: String? = null, val label: String = "")

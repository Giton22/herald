package dev.hermeskotlin.core.sessions

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Seeds a `session.create` with [instruction] (none when null) as the chat's first row, a hidden user message:
 * the agent reads it for the whole chat and no client shows it, the way Desktop seeds its onboarding runbook
 * (session_history._coerce_seed_history keeps `display_kind: "hidden"` from the wire). The gateway has no
 * other field for a client's standing instruction. A seeded chat's row is stored at once, not on the first prompt.
 */
internal fun JsonObjectBuilder.putStartInstruction(instruction: String?) {
    if (instruction.isNullOrBlank()) return
    putJsonArray("messages") {
        addJsonObject {
            put("role", "user")
            put("content", "$START_INSTRUCTION_PREFACE\n\n$instruction")
            put("display_kind", "hidden")
        }
    }
}

/** Tells the model where the hidden row comes from, so it follows it instead of answering it. */
internal const val START_INSTRUCTION_PREFACE =
    "[A standing instruction the user set in Herald, the app they are chatting from, for every new chat. " +
        "Follow it for the whole conversation; don't reply to it.]"

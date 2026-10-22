package dev.hermeskotlin.core.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * Subscription voice: the live voice of the talk-desktop dashboard plugin (Codex Live), which runs on the ChatGPT plan
 * the gateway signed Codex in with instead of per minute on an OpenAI key. The phone makes the same WebRTC call as for
 * GPT-Live; the plugin trades its offer for the answer (`/codexlive/session`) and hangs up (`/codexlive/stop`).
 *
 * The voice has no tools of its own. When it's asked for real work it sends `delegation.created`; with the plugin's
 * client handoff (its default) that's Herald's to run, as an ordinary Hermes turn in the open chat, and the reply goes
 * back as `delegation.context.append` for the voice to say in its own words.
 *
 * Reference client: the plugin's desktop/plugin.js (startLive, handleRealtimeEvent, handleDelegation).
 * Plugin: https://github.com/Synero/hermes-live-voice
 */

/** The plugin's sign-in to ChatGPT, as `/codex/status` reports it. */
@Serializable
data class SubscriptionVoiceStatus(
    val ok: Boolean = false,
    /** `valid` or `expired` (refreshed on use) once signed in; anything else is no sign-in. */
    val codexOauth: String? = null,
) {
    /** The voice can start: the plugin is there and signed in to ChatGPT. */
    val signedIn: Boolean get() = ok && codexOauth in SIGNED_IN
}

// Outside the class: a serializable class's companion is where its serializer is looked up.
private val SIGNED_IN = setOf("valid", "expired")

/** A call the plugin started: the vendor's [answer], and the [threadId] to hang up with. */
@Serializable
data class SubscriptionSession(
    val answer: String = "",
    val threadId: String? = null,
    /** `client`: Herald runs what the voice hands over. `server`: the plugin does, and Herald leaves it. */
    val handoff: String = "client",
)

/** What the plugin's voice sends over the call's `oai-events` channel, the parts Herald acts on. */
sealed interface SubscriptionEvent {
    data object Started : SubscriptionEvent

    /** A piece of what's being said, as it's heard: the person's ([LiveFragment.Speaker.User]) or the voice's. */
    data class Heard(val speaker: LiveFragment.Speaker, val text: String) : SubscriptionEvent

    /** Someone's turn ended, with all of what they said. */
    data class TurnDone(val speaker: LiveFragment.Speaker, val transcript: String) : SubscriptionEvent

    /** The voice handed work over: [request] is the task as it put it, blank when it gave none. */
    data class Delegation(val itemId: String, val request: String) : SubscriptionEvent

    data class Failure(val message: String) : SubscriptionEvent
}

private val eventJson = Json { ignoreUnknownKeys = true }

/** Reads one `oai-events` message of the plugin's voice; null for events Herald has no use for, or garbage. */
fun parseSubscriptionEvent(raw: String): SubscriptionEvent? {
    val event = runCatching { eventJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun speaker(role: String?) = when (role) {
        "user" -> LiveFragment.Speaker.User
        "assistant" -> LiveFragment.Speaker.Assistant
        else -> null
    }
    val item = event["item"] as? JsonObject
    return when (event.text("type")) {
        "session.started" -> SubscriptionEvent.Started
        "input_transcript.added" -> item?.text("text")?.takeIf { it.isNotEmpty() }
            ?.let { SubscriptionEvent.Heard(LiveFragment.Speaker.User, it) }
        "output_transcript.added" -> item?.text("text")?.takeIf { it.isNotEmpty() }
            ?.let { SubscriptionEvent.Heard(LiveFragment.Speaker.Assistant, it) }
        "turn.done" -> {
            val turn = event["turn"] as? JsonObject
            speaker(turn?.text("role"))?.let { SubscriptionEvent.TurnDone(it, turn?.text("transcript").orEmpty()) }
        }
        "delegation.created" -> {
            val id = item?.text("id")?.takeIf { it.isNotEmpty() } ?: event.text("delegation_item_id")?.takeIf { it.isNotEmpty() } ?: return null
            val content = (item?.get("content") as? JsonArray).orEmpty()
            SubscriptionEvent.Delegation(id, content.joinToString("") { ((it as? JsonObject)?.text("text")).orEmpty() }.trim())
        }
        "error" -> {
            val error = event["error"] as? JsonObject
            SubscriptionEvent.Failure(error?.text("message") ?: error?.text("code") ?: "Live voice error")
        }
        else -> null
    }
}

/** The events Herald sends the plugin's voice. */
object SubscriptionCommands {
    /** As much of a result as the desktop client hands over in one append. */
    const val RESULT_CHARS = 12_000

    /** Something about the work handed over as [itemId], for the voice to tell in its own words. */
    fun delegationResult(itemId: String, text: String) = buildJsonObject {
        put("type", "delegation.context.append")
        put("delegation_item_id", itemId)
        putJsonArray("content") {
            addJsonObject {
                put("type", "input_text")
                put("text", text.take(RESULT_CHARS))
            }
        }
    }.toString()
}

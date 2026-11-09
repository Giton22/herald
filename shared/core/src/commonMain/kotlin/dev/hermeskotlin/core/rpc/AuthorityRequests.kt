package dev.hermeskotlin.core.rpc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Rewrites requests for a gateway whose `/api/ws` runs on its session authority (`gateway.ready`
 * `session_authority`, the gateway-hosted Webapp listener). It checks each request against
 * tui_gateway/contracts/canonical_sessions.py, where every key is closed, so this does what Hermes Desktop's own
 * client does there (apps/desktop/src/api/canonical-protocol.ts):
 *
 * - `session.create` names the `gui` surface (stored as `desktop`), sends the reasoning pick as `reasoning` and the
 *   fast pick as `service_tier`, carries a `request_id` and drops what the contract has no field for (`cols`, and the
 *   start instruction's `messages`: such a chat starts without it).
 * - `prompt.submit` carries a `submission_id`.
 * - Every request about a chat opened with a `profile` carries that profile. The gateway routes a named profile's
 *   chat to that profile's own connection, which is the only one that holds it: a send without the profile is
 *   `permission_denied`.
 */
internal class AuthorityRequests(private val newId: () -> String = ::randomId) {
    private val lock = Mutex()

    /** Chat id → the profile it was opened with, for the chats opened on this socket with one. */
    private val profiles = mutableMapOf<String, String>()

    /** [params] for [method] as this gateway takes them. */
    suspend fun prepare(method: String, params: JsonObject): JsonObject {
        val sessionId = params.text("session_id")
        val profile = sessionId?.let { id -> lock.withLock { profiles[id] } }
        return when (method) {
            "session.create" -> create(params)
            "prompt.submit" -> params.withProfile(profile) {
                if (params.text("submission_id") == null && params.text("input_id") == null) put("submission_id", newId())
            }
            else -> params.withProfile(profile) {}
        }
    }

    /** Notes the chat [method] opened with [params] (as sent) and answered with [result]. */
    suspend fun answered(method: String, params: JsonObject, result: JsonElement) {
        if (method != "session.create" && method != "session.resume") return
        val profile = params.text("profile") ?: return
        val sessionId = (result as? JsonObject)?.text("session_id") ?: return
        lock.withLock { profiles[sessionId] = profile }
    }

    private fun create(params: JsonObject): JsonObject = buildJsonObject {
        params.forEach { (key, value) -> if (key in CREATE_KEYS) put(key, value) }
        put("source", GUI_SOURCE)
        params.text("reasoning_effort")?.let { put("reasoning", it) }
        val fast = (params["fast"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        if (params["service_tier"] == null && fast != null) put("service_tier", if (fast) "priority" else "normal")
        if (params["request_id"] == null) put("request_id", newId())
    }

    /** [this] with [profile] added when it has none, then [more]. */
    private inline fun JsonObject.withProfile(profile: String?, more: JsonObjectBuilder.() -> Unit): JsonObject {
        val addProfile = profile != null && this["profile"] == null
        return buildJsonObject {
            this@withProfile.forEach { (key, value) -> put(key, value) }
            if (addProfile) put("profile", profile)
            more()
        }
    }

    private companion object {
        /** The surface label a graphical client creates its chats with (gateway/session_policy.py SURFACES). */
        const val GUI_SOURCE = "gui"

        /**
         * The `session.create` keys sent on as they are: `CanonicalCreateParams` with ProfileParams' `profile`, less
         * those set here. The rest (`cols`, the start instruction's `messages`) it has no field for.
         */
        val CREATE_KEYS = setOf(
            "profile", "cwd", "row_cwd", "model", "provider", "base_url", "api_key", "toolsets", "reasoning",
            "max_turns", "ignore_rules", "yolo", "safe_mode", "ignore_user_config", "skills", "checkpoints",
            "accept_hooks", "pass_session_id", "editor", "title", "hidden", "follow_profile_config", "service_tier",
            "cwd_explicit", "run_budget", "verbose", "client_env", "client", "request_id",
        )
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

@OptIn(ExperimentalUuidApi::class)
private fun randomId(): String = Uuid.random().toString()

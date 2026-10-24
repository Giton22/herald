package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
private data class SessionPatch(
    val title: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
    val unread: Boolean? = null,
    val profile: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("expected_revision") val expectedRevision: Long? = null,
    @SerialName("expected_generation") val expectedGeneration: Long? = null,
)

/** `GET /api/sessions/{id}/mutation-snapshot`: the counters a write to the row must name. */
@Serializable
private data class MutationSnapshot(
    val exists: Boolean = false,
    @SerialName("runtime_revision") val revision: Long = 0,
    @SerialName("runtime_generation") val generation: Long? = null,
)

/**
 * What a write names so the gateway applies it once and only over the row as read: a fresh [requestId], and the
 * [revision] and [generation] the row had.
 */
private class MutationIdentity(val requestId: String, val revision: Long, val generation: Long?)

@Serializable
private data class SearchResponse(val results: List<SessionSummary> = emptyList())

/**
 * Stored-session REST surface of the dashboard (hermes_cli/web_routers/sessions.py) — the same
 * endpoints Hermes Desktop uses for its sidebar and transcript paging. Live sessions go over `/api/ws`.
 *
 * Every call takes the [dev.hermeskotlin.core.profiles.Profile] whose state.db it reads; null is the
 * profile the gateway was launched as.
 */
class SessionsApi(private val client: HttpClient) {

    /** `GET /api/sessions`, most recent activity first. Pinned rows past the window are appended by the server. */
    suspend fun list(
        url: GatewayUrl,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
        filter: SessionListFilter = SessionListFilter.Recent,
        profile: String? = null,
    ): ApiResult<SessionPage> = apiCall {
        client.get(url.resolve("api/sessions")) {
            profile?.let { parameter("profile", it) }
            parameter("limit", limit.coerceIn(1, 100))
            parameter("offset", offset)
            parameter("archived", filter.archived.wire)
            parameter("order", "recent")
            filter.source?.let { parameter("source", it) }
            if (filter.excludeSources.isNotEmpty()) parameter("exclude_sources", filter.excludeSources.joinToString(","))
        }
    }.map { it.body<SessionPage>() }

    /** `GET /api/sessions/search` — id prefix and full-text message matches, one row per conversation. */
    suspend fun search(url: GatewayUrl, query: String, limit: Int = 30, profile: String? = null): ApiResult<List<SessionSummary>> = apiCall {
        client.get(url.resolve("api/sessions/search")) {
            profile?.let { parameter("profile", it) }
            parameter("q", query)
            parameter("limit", limit)
        }
    }.map { response ->
        response.body<SearchResponse>().results.map { it.copy(snippet = it.snippet?.stripMatchMarkers()) }
    }

    /**
     * `GET /api/sessions/{id}/messages` — the newest [limit] rows, in chronological order, skipping the newest
     * [offset] (`order=latest` counts the offset back from the end, so a larger one reaches older rows).
     */
    suspend fun messages(
        url: GatewayUrl,
        sessionId: String,
        limit: Int = 200,
        profile: String? = null,
        offset: Int = 0,
    ): ApiResult<SessionMessagesPage> = apiCall {
        client.get(url.resolve("api/sessions/${sessionId.encodeURLPathPart()}/messages")) {
            profile?.let { parameter("profile", it) }
            parameter("limit", limit)
            if (offset > 0) parameter("offset", offset)
            parameter("order", "latest")
            parameter("include_compacted", true)
        }
    }.map { it.body<SessionMessagesPage>() }

    /** `GET /api/sessions/{id}` — the stored row, read for its token and cost totals. */
    suspend fun totals(url: GatewayUrl, sessionId: String, profile: String? = null): ApiResult<SessionTotals> = apiCall {
        client.get(url.resolve("api/sessions/${sessionId.encodeURLPathPart()}")) {
            profile?.let { parameter("profile", it) }
        }
    }.map { it.body<SessionTotals>() }

    /** Sets a title; an empty [title] clears it. Titles are unique per profile, so a clash is a 400 with a message. */
    suspend fun rename(url: GatewayUrl, sessionId: String, title: String, profile: String? = null): ApiResult<Unit> =
        patch(url, sessionId, SessionPatch(title = title, profile = profile))

    suspend fun setPinned(url: GatewayUrl, sessionId: String, pinned: Boolean, profile: String? = null): ApiResult<Unit> =
        patch(url, sessionId, SessionPatch(pinned = pinned, profile = profile))

    suspend fun setArchived(url: GatewayUrl, sessionId: String, archived: Boolean, profile: String? = null): ApiResult<Unit> =
        patch(url, sessionId, SessionPatch(archived = archived, profile = profile))

    /** Sets the gateway's read state (`last_read_at`), which every client shares; the chat's lineage follows. */
    suspend fun setUnread(url: GatewayUrl, sessionId: String, unread: Boolean, profile: String? = null): ApiResult<Unit> =
        patch(url, sessionId, SessionPatch(unread = unread, profile = profile))

    /**
     * Changes several of a chat's fields in one call; null leaves a field as it is. The gateway sets the title
     * first, so a title another chat has (a 400 saying it is already in use) changes nothing.
     */
    suspend fun update(
        url: GatewayUrl,
        sessionId: String,
        title: String? = null,
        archived: Boolean? = null,
        pinned: Boolean? = null,
        profile: String? = null,
    ): ApiResult<Unit> = patch(url, sessionId, SessionPatch(title = title, archived = archived, pinned = pinned, profile = profile))

    /** `DELETE /api/sessions/{id}`; an already-deleted session also succeeds. */
    suspend fun delete(url: GatewayUrl, sessionId: String, profile: String? = null): ApiResult<Unit> =
        mutate(url, sessionId, profile, gone = ApiResult.Success(Unit)) { identity ->
            client.delete(url.resolve("api/sessions/${sessionId.encodeURLPathPart()}")) {
                profile?.let { parameter("profile", it) }
                identity?.let {
                    parameter("request_id", it.requestId)
                    parameter("expected_revision", it.revision)
                    it.generation?.let { generation -> parameter("expected_generation", generation) }
                }
            }
        }

    // A new title isn't sent again over a race: it could overwrite one another device just gave the chat.
    private suspend fun patch(url: GatewayUrl, sessionId: String, body: SessionPatch): ApiResult<Unit> =
        mutate(url, sessionId, body.profile, gone = ApiResult.Failed(404, GONE_MESSAGE), retry = body.title == null) { identity ->
            client.patch(url.resolve("api/sessions/${sessionId.encodeURLPathPart()}")) {
                contentType(ContentType.Application.Json)
                setBody(
                    body.copy(
                        requestId = identity?.requestId,
                        expectedRevision = identity?.revision,
                        expectedGeneration = identity?.generation,
                    ),
                )
            }
        }

    /**
     * Sends a write to the row [sessionId] the way the gateway takes one: its mutation snapshot is read first, and
     * [send] names it. A row that is gone answers [gone]. A gateway too old to have snapshots (the route is missing,
     * or something in front of it answers with a page rather than JSON) gets the write with no identity. A write
     * that lost a race with another change to the row is read and sent again, once, when [retry]. The gateway's
     * refusal codes are said in words.
     */
    private suspend fun mutate(
        url: GatewayUrl,
        sessionId: String,
        profile: String?,
        gone: ApiResult<Unit>,
        retry: Boolean = true,
        send: suspend (MutationIdentity?) -> HttpResponse,
    ): ApiResult<Unit> {
        var result: ApiResult<Unit> = gone
        repeat(if (retry) MUTATION_ATTEMPTS else 1) {
            val snapshot = apiCall {
                client.get(url.resolve("api/sessions/${sessionId.encodeURLPathPart()}/mutation-snapshot")) {
                    profile?.let { parameter("profile", it) }
                }
            }
            val identity = when {
                snapshot.isMissingRoute() -> null
                snapshot !is ApiResult.Success -> return snapshot.map { }.inWords()
                snapshot.value.contentType()?.match(ContentType.Application.Json) != true -> null
                else -> when (val read = snapshot.map { it.body<MutationSnapshot>() }) {
                    is ApiResult.Success -> {
                        if (!read.value.exists) return gone
                        val generation = read.value.generation
                            ?: return ApiResult.Failed(0, "The gateway didn't say which version of the chat it has. Try again.")
                        MutationIdentity(newRequestId(), read.value.revision, generation)
                    }
                    else -> return read.map { }
                }
            }
            result = apiCall { send(identity) }.map { }
            if (identity == null || !result.lostRace()) return result.inWords()
        }
        return result.inWords()
    }

    companion object {
        const val PAGE_SIZE = 50

        /** How many times a write that keeps losing races with other changes to its row is sent. */
        private const val MUTATION_ATTEMPTS = 2
    }
}

/**
 * A 404 for a route the gateway doesn't have: the dashboard's "No such API endpoint", or FastAPI's own "Not Found".
 * Any other 404 (a profile that doesn't exist) is the answer.
 */
private fun ApiResult<*>.isMissingRoute(): Boolean =
    this is ApiResult.Failed && status == 404 && (message == "Not Found" || message.startsWith("No such API endpoint"))

private fun ApiResult<*>.lostRace(): Boolean =
    this is ApiResult.Failed && status == 409 && (message.contains("revision_conflict") || message.contains("stale_generation"))

/** The gateway's session-write refusal codes, said for the user; other messages (a taken title) pass as they are. */
private fun ApiResult<Unit>.inWords(): ApiResult<Unit> {
    val message = when (this) {
        is ApiResult.Failed -> message
        is ApiResult.Unavailable -> message
        else -> return this
    }
    val words = when (message.substringBefore(':').trim()) {
        "revision_conflict", "stale_generation" -> "The chat changed somewhere else meanwhile. Try again."
        "session_busy" -> "The chat is busy right now. Try again in a moment."
        "not_found" -> GONE_MESSAGE
        "permission_denied", "profile_mismatch" -> "The gateway didn't allow this change to the chat."
        "client_upgrade_required", "mutation_identity_required" -> "The gateway refused the change. Update Herald and try again."
        "owner_unreachable", "runtime_draining", "storage_unavailable" -> "The gateway can't change the chat right now. Try again in a moment."
        else -> return this
    }
    // As a Failed, so the words aren't put after "Can't reach the gateway": it was reached, and refused.
    return ApiResult.Failed((this as? ApiResult.Failed)?.status ?: 503, words)
}

private const val GONE_MESSAGE = "This chat no longer exists."

@OptIn(ExperimentalUuidApi::class)
private fun newRequestId(): String = Uuid.random().toString()

private fun String.stripMatchMarkers(): String = replace(">>>", "").replace("<<<", "")

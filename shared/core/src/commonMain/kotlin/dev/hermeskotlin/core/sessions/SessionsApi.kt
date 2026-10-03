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
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

@Serializable
private data class SessionPatch(
    val title: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
)

@Serializable
private data class SearchResponse(val results: List<SessionSummary> = emptyList())

/**
 * Stored-session REST surface of the dashboard (hermes_cli/web_routers/sessions.py) — the same
 * endpoints Hermes Desktop uses for its sidebar and transcript paging. Live sessions go over `/api/ws`.
 */
class SessionsApi(private val client: HttpClient) {

    /** `GET /api/sessions`, most recent activity first. Pinned rows past the window are appended by the server. */
    suspend fun list(
        url: GatewayUrl,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
        filter: SessionListFilter = SessionListFilter.Recent,
    ): ApiResult<SessionPage> = apiCall {
        client.get(url.resolve("api/sessions")) {
            parameter("limit", limit.coerceIn(1, 100))
            parameter("offset", offset)
            parameter("archived", filter.archived.wire)
            parameter("order", "recent")
            filter.source?.let { parameter("source", it) }
            if (filter.excludeSources.isNotEmpty()) parameter("exclude_sources", filter.excludeSources.joinToString(","))
        }
    }.map { it.body<SessionPage>() }

    /** `GET /api/sessions/search` — id prefix and full-text message matches, one row per conversation. */
    suspend fun search(url: GatewayUrl, query: String, limit: Int = 30): ApiResult<List<SessionSummary>> = apiCall {
        client.get(url.resolve("api/sessions/search")) {
            parameter("q", query)
            parameter("limit", limit)
        }
    }.map { response ->
        response.body<SearchResponse>().results.map { it.copy(snippet = it.snippet?.stripMatchMarkers()) }
    }

    /** `GET /api/sessions/{id}/messages` — the newest [limit] rows, in chronological order. */
    suspend fun messages(url: GatewayUrl, sessionId: String, limit: Int = 200): ApiResult<SessionMessagesPage> = apiCall {
        client.get(url.resolve("api/sessions/${sessionId.encodeURLPathPart()}/messages")) {
            parameter("limit", limit)
            parameter("order", "latest")
            parameter("include_compacted", true)
        }
    }.map { it.body<SessionMessagesPage>() }

    /** Sets a title; an empty [title] clears it. Titles are unique per profile, so a clash is a 400 with a message. */
    suspend fun rename(url: GatewayUrl, sessionId: String, title: String): ApiResult<Unit> =
        patch(url, sessionId, SessionPatch(title = title))

    suspend fun setPinned(url: GatewayUrl, sessionId: String, pinned: Boolean): ApiResult<Unit> =
        patch(url, sessionId, SessionPatch(pinned = pinned))

    suspend fun setArchived(url: GatewayUrl, sessionId: String, archived: Boolean): ApiResult<Unit> =
        patch(url, sessionId, SessionPatch(archived = archived))

    /** `DELETE /api/sessions/{id}`; an already-deleted session also succeeds. */
    suspend fun delete(url: GatewayUrl, sessionId: String): ApiResult<Unit> = apiCall {
        client.delete(url.resolve("api/sessions/${sessionId.encodeURLPathPart()}"))
    }.map { }

    private suspend fun patch(url: GatewayUrl, sessionId: String, body: SessionPatch): ApiResult<Unit> = apiCall {
        client.patch(url.resolve("api/sessions/${sessionId.encodeURLPathPart()}")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }.map { }

    companion object {
        const val PAGE_SIZE = 50
    }
}

private fun String.stripMatchMarkers(): String = replace(">>>", "").replace("<<<", "")

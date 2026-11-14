package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json

/**
 * The Kanban dashboard plugin's REST surface (`GET /api/plugins/kanban/...`), the same routes the web
 * dashboard's tab calls. Every call carries an explicit `board=` slug — never omit it (omitting falls
 * through to the gateway's `current` pointer, which would hide the tab's own selection; regression #20879).
 */
class KanbanApi(private val client: HttpClient) {

    /** One board grouped by column (`board=` is required; tenant/archive filters ride along). */
    suspend fun board(
        url: GatewayUrl,
        board: String,
        tenant: String? = null,
        includeArchived: Boolean = false,
    ): ApiResult<KanbanBoardResponse> = apiCall {
        client.get(url.resolve("api/plugins/kanban/board")) {
            parameter("board", board)
            tenant?.let { parameter("tenant", it) }
            if (includeArchived) parameter("include_archived", true)
        }
    }.map { it.body<KanbanBoardResponse>() }

    /** Every board on the gateway with task counts and the active slug. */
    suspend fun boards(url: GatewayUrl): ApiResult<KanbanBoardsResponse> = apiCall {
        client.get(url.resolve("api/plugins/kanban/boards"))
    }.map { it.body<KanbanBoardsResponse>() }

    /** One task with its comments, events, attachments, links and runs (the drawer payload). */
    suspend fun task(url: GatewayUrl, taskId: String, board: String): ApiResult<KanbanTaskDetailResponse> = apiCall {
        client.get(url.resolve("api/plugins/kanban/tasks/${taskId.encodeURLPathPart()}")) {
            parameter("board", board)
        }
    }.map { response -> lenientJson.decodeFromString<KanbanTaskDetailResponse>(response.bodyAsText()) }

    /** Worker stdout/stderr for one task; `tail` caps the bytes (the dashboard asks for 100 KB). */
    suspend fun log(
        url: GatewayUrl,
        taskId: String,
        board: String,
        tail: Int = 100_000,
    ): ApiResult<KanbanLogResponse> = apiCall {
        client.get(url.resolve("api/plugins/kanban/tasks/${taskId.encodeURLPathPart()}/log")) {
            parameter("board", board)
            parameter("tail", tail)
        }
    }.map { it.body<KanbanLogResponse>() }

    private companion object {
        /** The gateway writes `null` for fields a task doesn't have; those read as the default. */
        val lenientJson = Json(HermesJson) { coerceInputValues = true }
    }
}

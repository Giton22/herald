package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class KanbanApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun boardSendsBoardParamAndParsesColumns() = runTest {
        var path: String? = null
        var query: String? = null
        val api = KanbanApi(createHttpClient(MockEngine { request ->
            path = request.url.encodedPath
            query = request.url.encodedQuery
            respond(
                """
                {"columns":[
                  {"name":"ready","tasks":[{"id":"t_1","title":"APIs: the schedule off the wire","status":"ready","priority":10,"assignee":"coder","tenant":"herald",
                     "age":{"created_age_seconds":10800,"started_age_seconds":5400},
                     "link_counts":{"parents":0,"children":1},"comment_count":3,"progress":null,
                     "warnings":{"count":0}}]},
                  {"name":"running","tasks":[{"id":"t_2","title":"Shell: a clock that finishes the turn","status":"running","priority":0,"assignee":"coder",
                     "warnings":{"count":1,"highest_severity":"error"}}]}],
                 "tenants":["herald"],"assignees":["coder","reviewer"],"latest_event_id":42,"now":1794020000}
                """.trimIndent(),
                HttpStatusCode.OK, json,
            )
        }))

        val board = assertIs<ApiResult.Success<KanbanBoardResponse>>(api.board(url, "waypoint")).value

        assertEquals("/api/plugins/kanban/board", path)
        assertEquals("board=waypoint", query)
        assertEquals(listOf("ready", "running"), board.columns.map { it.name })
        val ready = board.columns[0].tasks.single()
        assertEquals("t_1", ready.id)
        assertEquals(10, ready.priority)
        assertEquals(10800, ready.age?.createdAgeSeconds)
        assertEquals(1, ready.linkCounts?.children)
        assertEquals(3, ready.commentCount)
        assertNull(board.columns[0].tasks.single().progress)
        assertEquals(listOf("herald"), board.tenants)
        assertEquals(listOf("coder", "reviewer"), board.assignees)
        assertEquals(42, board.latestEventId)
    }

    @Test
    fun boardWithFiltersKeepsBoardParam() = runTest {
        var query: String? = null
        val api = KanbanApi(createHttpClient(MockEngine { request ->
            query = request.url.encodedQuery
            respond("""{"columns":[],"tenants":[],"assignees":[],"latest_event_id":0,"now":1}""", HttpStatusCode.OK, json)
        }))

        val result = api.board(url, "herald", tenant = "herald", includeArchived = true)

        assertIs<ApiResult.Success<KanbanBoardResponse>>(result)
        assertEquals("board=herald&tenant=herald&include_archived=true", query)
    }

    @Test
    fun boardsParsesCurrentAndCounts() = runTest {
        val api = KanbanApi(createHttpClient(MockEngine {
            respond(
                """
                {"boards":[
                  {"slug":"waypoint","name":"Waypoint","counts":{"running":1,"todo":2},"total":3,"is_current":true,
                   "default_workspace_kind":"dir","project_name":null,"archived":false,"description":"","icon":"","color":"","default_workdir":null,"project_id":null,"created_at":1793647047},
                  {"slug":"herald","name":"Herald / Kyllene","counts":{"done":2},"total":2,"is_current":false,
                   "default_workspace_kind":"scratch","project_name":null,"archived":false,"description":"","icon":"","color":"","default_workdir":null,"project_id":null,"created_at":null}
                ],"current":"waypoint"}
                """.trimIndent(),
                HttpStatusCode.OK, json,
            )
        }))

        val res = assertIs<ApiResult.Success<KanbanBoardsResponse>>(api.boards(url)).value

        assertEquals("waypoint", res.current)
        assertEquals(2, res.boards.size)
        assertEquals(3, res.boards[0].total)
        assertEquals(mapOf("running" to 1, "todo" to 2), res.boards[0].counts)
        assertEquals(true, res.boards[0].isCurrent)
        assertEquals("scratch", res.boards[1].defaultWorkspaceKind)
    }

    @Test
    fun taskDetailAndLogKeepBoardParam() = runTest {
        val paths = mutableListOf<String>()
        val api = KanbanApi(createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when {
                request.url.encodedPath.endsWith("/log") -> respond(
                    """{"task_id":"t_1","path":"/tmp/kanban/t_1.log","exists":true,"size_bytes":123,"content":"hello","truncated":false}""",
                    HttpStatusCode.OK, json,
                )
                else -> respond(
                    """
                    {"task":{"id":"t_1","title":"APIs: the schedule off the wire","status":"ready",
                      "age":{"created_age_seconds":3600},"latest_summary":"Wrote the bridge.","current_run_started_at":1794010000},
                     "comments":[{"id":1,"task_id":"t_1","author":"reviewer","body":"Looks good.","created_at":1794011000}],
                     "events":[{"id":9,"task_id":"t_1","kind":"claimed","payload":{"profile":"coder"},"created_at":1794010500,"run_id":7}],
                     "attachments":[{"id":5,"task_id":"t_1","filename":"diff.patch","content_type":"text/plain","size":42,"uploaded_by":"coder","stored_path":"/x/diff.patch","created_at":1794010600}],
                     "links":{"parents":["t_0"],"children":["t_2"]},
                     "link_tasks":[{"id":"t_0","title":"Parent","status":"done"}],
                     "child_results":[{"id":"t_2","title":"Child","status":"done","latest_summary":"Done","result":"ok"}],
                     "runs":[{"id":7,"task_id":"t_1","profile":"coder","status":"done","started_at":1794010500,"ended_at":1794010900,
                       "outcome":"completed","summary":"All green","metadata":{},"error":null}]}
                    """.trimIndent(),
                    HttpStatusCode.OK, json,
                )
            }
        }))

        val detail = assertIs<ApiResult.Success<KanbanTaskDetailResponse>>(api.task(url, "t_1", "waypoint")).value
        assertEquals("t_1", detail.task?.id)
        assertEquals("Wrote the bridge.", detail.task?.latestSummary)
        assertEquals(listOf("reviewer"), detail.comments.map { it.author })
        assertEquals("claimed", detail.events.single().kind)
        assertEquals("diff.patch", detail.attachments.single().filename)
        assertEquals(listOf("t_0"), detail.links.parents)
        assertEquals("Parent", detail.linkTasks.single().title)
        assertEquals("ok", detail.childResults.single().result)
        assertEquals("completed", detail.runs.single().outcome)

        val log = assertIs<ApiResult.Success<KanbanLogResponse>>(api.log(url, "t_1", "waypoint")).value
        assertEquals("hello", log.content)
        assertEquals(true, log.exists)
        assertEquals(listOf("/api/plugins/kanban/tasks/t_1", "/api/plugins/kanban/tasks/t_1/log"), paths)
    }

    @Test
    fun sessionExpiryAndFailuresClassify() = runTest {
        val expired = KanbanApi(createHttpClient(MockEngine { respond("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized, json) }))
        assertEquals(ApiResult.SessionExpired, expired.boards(url))

        val missing = KanbanApi(createHttpClient(MockEngine { respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json) }))
        val failed = assertIs<ApiResult.Failed>(missing.board(url, "nope"))
        assertEquals(404, failed.status)
    }

    @Test
    fun nullTaskFieldsReadAsDefaults() = runTest {
        val api = KanbanApi(createHttpClient(MockEngine {
            respond(
                """{"task":{"id":"t_x","title":null,"status":null,"priority":null,"assignee":null,"body":null},"comments":[],"events":[],"attachments":[],"links":{"parents":null,"children":null},"link_tasks":[],"child_results":[],"runs":[]}""",
                HttpStatusCode.OK, json,
            )
        }))

        val detail = assertIs<ApiResult.Success<KanbanTaskDetailResponse>>(api.task(url, "t_x", "default")).value
        assertEquals("t_x", detail.task?.id)
        assertEquals("", detail.task?.title)
        assertEquals("todo", detail.task?.status)
        assertEquals(0, detail.task?.priority)
        assertEquals(emptyList<String>(), detail.links.parents)
    }
}

package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanBoardMeta
import dev.hermeskotlin.core.kanban.KanbanBoardsResponse
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

/** T4 (`docs/WIDGETS-SPEC.md` §11): the pin drives an explicit `board=`; a deleted pin falls back and the snapshot records it. */
class KanbanBoardPinningTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    private fun boards(current: String, vararg slugs: String) =
        KanbanBoardsResponse(boards = slugs.map { KanbanBoardMeta(slug = it, name = it) }, current = current)

    @Test
    fun aLivePinWinsOverTheCurrentPointer() {
        val resolved = KanbanWidgetAssembler.resolveBoard(boards("default", "default", "waypoint"), pin = "waypoint")
        assertEquals("waypoint", resolved?.slug)
    }

    @Test
    fun aDeletedPinFallsBackToCurrentThenToTheFirstBoard() {
        assertEquals("default", KanbanWidgetAssembler.resolveBoard(boards("default", "default", "waypoint"), pin = "gone")?.slug)
        assertEquals(
            "first",
            KanbanWidgetAssembler.resolveBoard(boards(current = "missing", "first", "second"), pin = null)?.slug,
        )
        assertNull(KanbanWidgetAssembler.resolveBoard(KanbanBoardsResponse(boards = emptyList(), current = "default"), pin = "x"))
    }

    @Test
    fun theSnapshotRecordsTheSlugActuallyReadNotThePin() {
        // The pin named a deleted board; the refresh fell back to `default`, so that is what the
        // snapshot (and its cache key) carries.
        val pin = "gone"
        val boards = boards("default", "default", "waypoint")
        val resolved = KanbanWidgetAssembler.resolveBoard(boards, pin)
        val snapshot = KanbanWidgetAssembler.assemble(
            gatewayUrl = url.value,
            boards = boards,
            board = null,
            boardSlug = resolved?.slug,
            serverNowSeconds = 1,
            deviceNowSeconds = 2,
        )
        assertEquals("default", snapshot.boardSlug)
        assertEquals("default", snapshot.boardName)
    }

    @Test
    fun everyBoardReadPassesTheResolvedSlugExplicitly() = runTest {
        val queries = mutableListOf<String>()
        val api = KanbanApi(createHttpClient(MockEngine { request ->
            queries += request.url.encodedQuery.orEmpty()
            respond("{\"columns\":[],\"tenants\":[],\"assignees\":[],\"latest_event_id\":0,\"now\":1}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))

        assertIs<ApiResult.Success<*>>(api.board(url, board = "waypoint"))
        assertIs<ApiResult.Success<*>>(api.task(url, taskId = "t1", board = "waypoint"))
        assertIs<ApiResult.Success<*>>(api.log(url, taskId = "t1", board = "waypoint"))

        // No call may lean on the gateway's default board pointer (regression #20879).
        assertEquals(listOf("board=waypoint", "board=waypoint", "board=waypoint&tail=100000"), queries)
    }
}

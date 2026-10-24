package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionsApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SessionsApi(createHttpClient(MockEngine(handler)))

    /** Answers each write's mutation-snapshot read with [snapshot]; every other request goes to [handler]. */
    private fun writesApi(
        snapshot: String = """{"session_id":"s","exists":true,"runtime_revision":7,"runtime_generation":2}""",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = api { request ->
        if (request.url.encodedPath.endsWith("/mutation-snapshot")) respond(snapshot, HttpStatusCode.OK, json) else handler(request)
    }

    private fun HttpRequestData.bodyText() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    @Test
    fun listParsesRowsAndSendsRecentOrder() = runTest {
        var query: String? = null
        val api = api { request ->
            query = request.url.encodedQuery
            respond(
                """{"sessions":[{"id":"s1","title":null,"preview":"fix the build\nplease","source":"desktop",
                   "model":"claude","started_at":1700000000.5,"last_active":1700000100.0,"message_count":4,
                   "pinned":true,"archived":false,"is_active":false,"profile":"default","unknown_key":1}],
                   "total":1,"limit":50,"offset":0,"storage":{}}""",
                HttpStatusCode.OK, json,
            )
        }

        val page = assertIs<ApiResult.Success<SessionPage>>(api.list(url)).value

        assertEquals("limit=50&offset=0&archived=exclude&order=recent&exclude_sources=acp%2Ccron%2Ckanban%2Coneshot%2Csubagent%2Ctool", query)
        val row = page.sessions.single()
        assertEquals("fix the build", row.displayTitle)
        assertEquals(1700000100.0, row.activityAt)
        assertTrue(row.pinned)
    }

    @Test
    fun archivedViewMapsToItsQuery() = runTest {
        val queries = mutableListOf<String>()
        val api = api { request ->
            queries += request.url.encodedQuery
            respond("""{"sessions":[],"total":0}""", HttpStatusCode.OK, json)
        }

        api.list(url, filter = SessionListFilter.Archived)

        assertEquals("limit=50&offset=0&archived=only&order=recent", queries.single())
    }

    @Test
    fun patchSendsTheChangedFieldWithTheRowsMutationIdentity() = runTest {
        var body: String? = null
        var method: HttpMethod? = null
        var path: String? = null
        var snapshotQuery: String? = null
        val api = api { request ->
            if (request.url.encodedPath.endsWith("/mutation-snapshot")) {
                snapshotQuery = request.url.encodedPath + "?" + request.url.encodedQuery
                return@api respond("""{"session_id":"a b","exists":true,"runtime_revision":7,"runtime_generation":2}""", HttpStatusCode.OK, json)
            }
            method = request.method
            path = request.url.encodedPath
            body = request.bodyText()
            respond("""{"ok":true,"title":"x","pinned":true}""", HttpStatusCode.OK, json)
        }

        assertIs<ApiResult.Success<Unit>>(api.setPinned(url, "a b", true, profile = "work"))

        assertEquals("/api/sessions/a%20b/mutation-snapshot?profile=work", snapshotQuery)
        assertEquals(HttpMethod.Patch, method)
        assertEquals("/api/sessions/a%20b", path)
        val sent = Json.parseToJsonElement(body!!).jsonObject
        assertEquals(setOf("pinned", "profile", "request_id", "expected_revision", "expected_generation"), sent.keys)
        assertEquals("7", sent.getValue("expected_revision").jsonPrimitive.content)
        assertEquals("2", sent.getValue("expected_generation").jsonPrimitive.content)
        assertTrue(sent.getValue("request_id").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun deleteNamesTheRowsMutationIdentityInItsQuery() = runTest {
        var query: String? = null
        val api = writesApi(snapshot = """{"session_id":"s1","exists":true,"runtime_revision":3,"runtime_generation":0}""") { request ->
            assertEquals(HttpMethod.Delete, request.method)
            query = request.url.encodedQuery
            respond("""{"ok":true,"deleted_ids":["s1"]}""", HttpStatusCode.OK, json)
        }

        assertIs<ApiResult.Success<Unit>>(api.delete(url, "s1", profile = "work"))

        val params = query!!.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
        assertEquals("work", params["profile"])
        assertEquals("3", params["expected_revision"])
        assertEquals("0", params["expected_generation"])
        assertTrue(params["request_id"].orEmpty().isNotBlank())
    }

    @Test
    fun deletingARowThatIsAlreadyGoneSucceedsWithoutAWrite() = runTest {
        val api = writesApi(snapshot = """{"session_id":"s1","exists":false,"runtime_revision":0,"runtime_generation":null}""") {
            error("no write for a row that is gone")
        }

        assertIs<ApiResult.Success<Unit>>(api.delete(url, "s1"))
    }

    @Test
    fun aWriteThatLostARaceIsReadAndSentAgainOnce() = runTest {
        val revisions = mutableListOf<String>()
        val requestIds = mutableListOf<String>()
        var reads = 0
        val api = api { request ->
            if (request.url.encodedPath.endsWith("/mutation-snapshot")) {
                reads++
                return@api respond("""{"session_id":"s1","exists":true,"runtime_revision":$reads,"runtime_generation":1}""", HttpStatusCode.OK, json)
            }
            val sent = Json.parseToJsonElement(request.bodyText()).jsonObject
            revisions += sent.getValue("expected_revision").jsonPrimitive.content
            requestIds += sent.getValue("request_id").jsonPrimitive.content
            respond("""{"detail":"revision_conflict"}""", HttpStatusCode.Conflict, json)
        }

        val result = assertIs<ApiResult.Failed>(api.setArchived(url, "s1", true))

        assertEquals(listOf("1", "2"), revisions)
        assertEquals(2, requestIds.toSet().size)
        assertEquals("The chat changed somewhere else meanwhile. Try again.", result.message)
    }

    @Test
    fun aNewTitleThatLostARaceIsNotSentAgain() = runTest {
        var writes = 0
        val api = writesApi {
            writes++
            respond("""{"detail":"revision_conflict"}""", HttpStatusCode.Conflict, json)
        }

        assertIs<ApiResult.Failed>(api.rename(url, "s1", "Mine"))

        assertEquals(1, writes)
    }

    @Test
    fun aRefusedOrUnreadableSnapshotIsReturnedWithoutAWrite() = runTest {
        val unreadable = writesApi(snapshot = "") { error("no write after an unreadable snapshot") }
        val missingProfile = api { request ->
            if (request.url.encodedPath.endsWith("/mutation-snapshot")) {
                respond("""{"detail":"Profile 'gone' does not exist."}""", HttpStatusCode.NotFound, json)
            } else {
                error("no write for a missing profile")
            }
        }

        assertEquals("Profile 'gone' does not exist.", assertIs<ApiResult.Failed>(missingProfile.delete(url, "s1", profile = "gone")).message)
        assertIs<ApiResult.Failed>(unreadable.setPinned(url, "s1", true))
    }

    @Test
    fun aChangeToARowThatIsGoneSaysSoWithoutAWrite() = runTest {
        val api = writesApi(snapshot = """{"session_id":"s1","exists":false,"runtime_revision":0,"runtime_generation":null}""") {
            error("no write for a row that is gone")
        }

        assertEquals("This chat no longer exists.", assertIs<ApiResult.Failed>(api.setPinned(url, "s1", true)).message)
    }

    @Test
    fun aBusyRefusalIsSaidInWords() = runTest {
        val api = writesApi { respond("""{"detail":"session_busy"}""", HttpStatusCode.Conflict, json) }

        assertEquals("The chat is busy right now. Try again in a moment.", assertIs<ApiResult.Failed>(api.delete(url, "s1")).message)
    }

    @Test
    fun aGatewayWithoutSnapshotsGetsTheWriteWithNoIdentity() = runTest {
        // The dashboard's catch-all for an /api route it lacks, and FastAPI's own.
        listOf("No such API endpoint: /api/sessions/s1/mutation-snapshot", "Not Found").forEach { missing ->
            var body: String? = null
            val api = api { request ->
                if (request.url.encodedPath.endsWith("/mutation-snapshot")) {
                    return@api respond("""{"detail":"$missing"}""", HttpStatusCode.NotFound, json)
                }
                body = request.bodyText()
                respond("""{"ok":true}""", HttpStatusCode.OK, json)
            }

            assertIs<ApiResult.Success<Unit>>(api.setPinned(url, "s1", true))

            assertEquals("""{"pinned":true}""", body, missing)
        }
    }

    @Test
    fun theGatewaysReadStateIsReadAndWritten() = runTest {
        var body: String? = null
        val api = writesApi { request ->
            if (request.method == HttpMethod.Patch) {
                body = Json.parseToJsonElement(request.bodyText()).jsonObject.getValue("unread").toString()
                respond("""{"ok":true}""", HttpStatusCode.OK, json)
            } else {
                respond("""{"sessions":[{"id":"tg","source":"telegram","unread":true},{"id":"old","unread":0},{"id":"none"}]}""", HttpStatusCode.OK, json)
            }
        }

        val rows = assertIs<ApiResult.Success<SessionPage>>(api.list(url)).value.sessions
        assertIs<ApiResult.Success<Unit>>(api.setUnread(url, "tg", unread = false))

        assertEquals(listOf(true, false, false), rows.map { it.unread })
        assertEquals("false", body)
    }

    @Test
    fun renameClashSurfacesServerMessage() = runTest {
        val api = writesApi { respond("""{"detail":"Title already in use"}""", HttpStatusCode.BadRequest, json) }

        val result = assertIs<ApiResult.Failed>(api.rename(url, "s1", "Dup"))
        assertEquals("Title already in use", result.message)
    }

    @Test
    fun searchStripsSnippetMarkers() = runTest {
        val api = api {
            respond(
                """{"results":[{"session_id":"s2","id":"s2","title":"Deploy","snippet":"run >>>deploy<<< now",
                   "role":"user","last_active":null,"session_started":1.0,"message_count":2}]}""",
                HttpStatusCode.OK, json,
            )
        }

        val hit = assertIs<ApiResult.Success<List<SessionSummary>>>(api.search(url, "depl")).value.single()
        assertEquals("run deploy now", hit.snippet)
        assertEquals("Deploy", hit.displayTitle)
    }

    @Test
    fun messagesExtractTextFromStringsPartsAndToolCalls() = runTest {
        val api = api {
            respond(
                """{"session_id":"s1","profile":"default","messages":[
                   {"id":1,"role":"user","content":"hi","timestamp":1.0},
                   {"id":2,"role":"user","content":[{"type":"text","text":"look"},{"type":"image_url","image_url":{}}]},
                   {"id":3,"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function",
                     "function":{"name":"terminal","arguments":"{}"}}]},
                   {"id":4,"role":"tool","content":"ok","tool_call_id":"c1","tool_name":"terminal"},
                   {"id":5,"role":"user","content":"[CONTEXT SUMMARY]","display_kind":"hidden"}],
                   "pagination":{"limit":200,"offset":0,"order":"latest","returned":5}}""",
                HttpStatusCode.OK, json,
            )
        }

        val messages = assertIs<ApiResult.Success<SessionMessagesPage>>(api.messages(url, "s1")).value.messages
        assertEquals("hi", messages[0].text)
        assertEquals("look", messages[1].text)
        assertEquals(listOf("terminal"), messages[2].calledTools)
        assertTrue(messages[4].isHidden)
    }

    @Test
    fun expiredSessionIsReported() = runTest {
        val api = api { respond("""{"detail":"Not authenticated"}""", HttpStatusCode.Unauthorized, json) }
        assertEquals(ApiResult.SessionExpired, api.delete(url, "s1"))
    }
}

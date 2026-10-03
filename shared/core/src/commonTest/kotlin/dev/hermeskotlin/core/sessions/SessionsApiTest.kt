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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionsApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SessionsApi(createHttpClient(MockEngine(handler)))

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

        assertEquals("limit=50&offset=0&archived=exclude&order=recent&exclude_sources=cron", query)
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
    fun patchSendsOnlyTheChangedField() = runTest {
        var body: String? = null
        var method: HttpMethod? = null
        var path: String? = null
        val api = api { request ->
            method = request.method
            path = request.url.encodedPath
            body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("""{"ok":true,"title":"x","pinned":true}""", HttpStatusCode.OK, json)
        }

        assertIs<ApiResult.Success<Unit>>(api.setPinned(url, "a b", true))

        assertEquals(HttpMethod.Patch, method)
        assertEquals("/api/sessions/a%20b", path)
        assertEquals("""{"pinned":true}""", body)
    }

    @Test
    fun renameClashSurfacesServerMessage() = runTest {
        val api = api { respond("""{"detail":"Title already in use"}""", HttpStatusCode.BadRequest, json) }

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

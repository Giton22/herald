package dev.hermeskotlin.core.plugins

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

class PluginsApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun listsPluginsWithThePageEachBrings() = runTest {
        var query: String? = null
        val api = PluginsApi(createHttpClient(MockEngine { request ->
            query = request.url.encodedQuery
            respond(
                """[{"name":"example-board","label":"Example Board","description":"A board of example cards",
                     "icon":"Puzzle","version":"0.4.0","tab":{"path":"/example-board","position":"end"},"slots":[],
                     "entry":"dist/index.js","css":"dist/style.css","has_api":true,"source":"user"},
                    {"name":"enhance-prompt","label":"Enhance Prompt","description":"Backend for the composer action.",
                     "version":"0.4.1","tab":{"path":"/enhance-prompt","position":"end","hidden":true},"source":"user"},
                    {"name":"no-page","label":"No page","description":"A widget, no tab.","version":"1.0.0","source":"bundled"}]""",
                HttpStatusCode.OK, json,
            )
        }))

        val plugins = assertIs<ApiResult.Success<List<DashboardPlugin>>>(api.list(url, "work")).value

        assertEquals("profile=work", query)
        assertEquals(listOf("example-board", "enhance-prompt", "no-page"), plugins.map { it.name })
        assertEquals("/example-board", plugins[0].openPath)
        // A hidden tab is the dashboard's own plumbing, and no tab at all is nothing to open.
        assertNull(plugins[1].openPath)
        assertNull(plugins[2].openPath)
        assertEquals("0.4.0", plugins[0].version)
        assertEquals("user", plugins[0].source)
        assertEquals("A board of example cards", plugins[0].description)
    }

    @Test
    fun nullFieldsReadAsTheirDefaults() = runTest {
        val api = PluginsApi(createHttpClient(MockEngine {
            respond(
                """[{"name":"sparse","label":null,"description":null,"version":null,"icon":null,"tab":null,"source":"bundled"}]""",
                HttpStatusCode.OK, json,
            )
        }))

        val plugin = assertIs<ApiResult.Success<List<DashboardPlugin>>>(api.list(url, null)).value.single()

        assertEquals("sparse", plugin.label)
        assertEquals("", plugin.description)
        assertNull(plugin.version)
        assertNull(plugin.openPath)
    }

    @Test
    fun sessionExpiryAndFailuresClassify() = runTest {
        val expired = PluginsApi(createHttpClient(MockEngine { respond("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized, json) }))
        assertEquals(ApiResult.SessionExpired, expired.list(url, null))

        val missing = PluginsApi(createHttpClient(MockEngine { respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json) }))
        val failed = assertIs<ApiResult.Failed>(missing.list(url, null))
        assertEquals(404, failed.status)
    }
}

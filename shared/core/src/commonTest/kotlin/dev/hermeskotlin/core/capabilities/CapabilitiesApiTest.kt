package dev.hermeskotlin.core.capabilities

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CapabilitiesApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun skillsCarryStateAndProvenanceForTheProfile() = runTest {
        var query: String? = null
        val api = CapabilitiesApi(createHttpClient(MockEngine { request ->
            query = request.url.encodedQuery
            respond(
                """[{"name":"arxiv","description":"Search papers","category":"research","enabled":false,"usage":4,"provenance":"bundled"},
                   {"name":"deploy-notes","description":"","category":null,"enabled":true,"usage":0,"provenance":"agent"}]""",
                HttpStatusCode.OK, json,
            )
        }))

        val skills = assertIs<ApiResult.Success<List<Skill>>>(api.skills(url, "ruby")).value

        assertEquals("profile=ruby", query)
        assertFalse(skills[0].enabled)
        assertEquals("bundled", skills[0].provenance)
        assertEquals(null, skills[1].category)
    }

    @Test
    fun nullFieldsReadAsTheirDefaults() = runTest {
        val api = CapabilitiesApi(createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/mcp/servers" -> respond(
                    """{"servers":[{"name":"remote","transport":"http","url":"https://mcp.example.com/mcp","command":null,"args":null,"enabled":true,"plugin":null}]}""",
                    HttpStatusCode.OK, json,
                )
                else -> respond("""[{"name":"notes","description":null,"category":null,"enabled":true,"usage":null,"provenance":null}]""", HttpStatusCode.OK, json)
            }
        }))

        val server = assertIs<ApiResult.Success<List<McpServer>>>(api.mcpServers(url, null)).value.single()
        assertEquals(emptyList(), server.args)
        assertEquals("https://mcp.example.com/mcp", server.target)

        val skill = assertIs<ApiResult.Success<List<Skill>>>(api.skills(url, null)).value.single()
        assertEquals("", skill.description)
        assertEquals("agent", skill.provenance)
    }

    @Test
    fun togglesSendTheBodyEachRouteExpects() = runTest {
        val seen = mutableListOf<String>()
        val api = CapabilitiesApi(createHttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            seen += "${request.url.encodedPath} ${request.body.toByteArray().decodeToString()}"
            respond("""{"ok":true}""", HttpStatusCode.OK, json)
        }))

        assertIs<ApiResult.Success<Unit>>(api.setSkillEnabled(url, null, "arxiv", true))
        assertIs<ApiResult.Success<Unit>>(api.setToolsetEnabled(url, null, "browser", false))
        assertIs<ApiResult.Success<Unit>>(api.setMcpServerEnabled(url, null, "github", false))

        assertEquals(
            listOf(
                """/api/skills/toggle {"name":"arxiv","enabled":true}""",
                """/api/tools/toolsets/browser {"enabled":false}""",
                """/api/mcp/servers/github/enabled {"enabled":false}""",
            ),
            seen,
        )
    }

    @Test
    fun toolsetsAndServers() = runTest {
        val api = CapabilitiesApi(createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/tools/toolsets" -> respond(
                    """[{"name":"web","label":"Web Search","description":"Search the web","platform":"cli","platform_label":"CLI",
                       "enabled":true,"available":true,"configured":false,"tools":["web_search","web_extract"]}]""",
                    HttpStatusCode.OK, json,
                )
                "/api/mcp/servers" -> respond(
                    """{"servers":[{"name":"github","transport":"stdio","url":null,"command":"npx","args":["-y","@mcp/github"],
                       "env":["GITHUB_TOKEN"],"auth":null,"oauth_tokens_present":null,"enabled":true,"tools":null,"plugin":null}]}""",
                    HttpStatusCode.OK, json,
                )
                else -> respond(
                    """{"ok":true,"tools":[{"name":"search_repos","description":"Find repos"}],"prompts":0,"resources":0}""",
                    HttpStatusCode.OK, json,
                )
            }
        }))

        val toolset = assertIs<ApiResult.Success<List<Toolset>>>(api.toolsets(url, null)).value.single()
        assertEquals("Web Search", toolset.label)
        assertFalse(toolset.configured)
        assertEquals(2, toolset.tools.size)

        val server = assertIs<ApiResult.Success<List<McpServer>>>(api.mcpServers(url, null)).value.single()
        assertEquals("npx -y @mcp/github", server.target)

        val test = assertIs<ApiResult.Success<McpTestResult>>(api.testMcpServer(url, null, "github")).value
        assertTrue(test.ok)
        assertEquals("search_repos", test.tools.single().name)
    }
}

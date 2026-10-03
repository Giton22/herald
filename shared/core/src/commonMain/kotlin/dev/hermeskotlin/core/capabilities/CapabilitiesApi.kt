package dev.hermeskotlin.core.capabilities

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A skill the profile's agent can load, as `GET /api/skills` lists it (disabled ones included). */
@Serializable
data class Skill(
    val name: String,
    val description: String = "",
    val category: String? = null,
    val enabled: Boolean = true,
    /** `bundled` with Hermes, from the `hub`, `external` (a mounted folder), or `agent` (written by the agent or by hand). */
    val provenance: String = "agent",
    /** How often the agent has used it lately. */
    val usage: Int = 0,
)

/** A toolset the agent can be given (web, browser, terminal, …), from `GET /api/tools/toolsets`. */
@Serializable
data class Toolset(
    val name: String,
    val label: String = name,
    val description: String = "",
    val enabled: Boolean = false,
    /** False when it needs an API key or setup the gateway doesn't have yet. */
    val configured: Boolean = true,
    val tools: List<String> = emptyList(),
)

/** A configured MCP server, secrets left out, from `GET /api/mcp/servers`. */
@Serializable
data class McpServer(
    val name: String,
    /** `http`, `stdio` or `unknown`. */
    val transport: String = "unknown",
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val enabled: Boolean = true,
    /** Set when a plugin provides the server; those can't be switched from here. */
    val plugin: String? = null,
) {
    /** What it connects to: the URL, or the command line. */
    val target: String get() = url ?: listOfNotNull(command, args.joinToString(" ").takeIf { it.isNotBlank() }).joinToString(" ")
}

/** What a connection test found: the server's tools, or why it couldn't connect. */
@Serializable
data class McpTestResult(
    val ok: Boolean = false,
    val error: String? = null,
    val tools: List<McpTool> = emptyList(),
)

@Serializable
data class McpTool(val name: String, val description: String = "")

@Serializable
private data class McpServersResponse(val servers: List<McpServer> = emptyList())

@Serializable
private data class SkillToggle(val name: String, val enabled: Boolean)

@Serializable
private data class EnabledToggle(val enabled: Boolean)

/**
 * The profile's skills, toolsets and MCP servers, through the dashboard routes Desktop's settings
 * use. A switch is saved to the profile's config and applies from the next chat on.
 */
class CapabilitiesApi(private val client: HttpClient) {

    suspend fun skills(url: GatewayUrl, profile: String?): ApiResult<List<Skill>> = apiCall {
        client.get(url.resolve("api/skills")) { profile(profile) }
    }.map { it.body<List<Skill>>() }

    suspend fun setSkillEnabled(url: GatewayUrl, profile: String?, name: String, enabled: Boolean): ApiResult<Unit> = apiCall {
        client.put(url.resolve("api/skills/toggle")) {
            profile(profile)
            contentType(ContentType.Application.Json)
            setBody(SkillToggle(name, enabled))
        }
    }.map { }

    suspend fun toolsets(url: GatewayUrl, profile: String?): ApiResult<List<Toolset>> = apiCall {
        client.get(url.resolve("api/tools/toolsets")) { profile(profile) }
    }.map { it.body<List<Toolset>>() }

    suspend fun setToolsetEnabled(url: GatewayUrl, profile: String?, name: String, enabled: Boolean): ApiResult<Unit> = apiCall {
        client.put(url.resolve("api/tools/toolsets/${name.encodeURLPathPart()}")) {
            profile(profile)
            contentType(ContentType.Application.Json)
            setBody(EnabledToggle(enabled))
        }
    }.map { }

    suspend fun mcpServers(url: GatewayUrl, profile: String?): ApiResult<List<McpServer>> = apiCall {
        client.get(url.resolve("api/mcp/servers")) { profile(profile) }
    }.map { it.body<McpServersResponse>().servers }

    suspend fun setMcpServerEnabled(url: GatewayUrl, profile: String?, name: String, enabled: Boolean): ApiResult<Unit> = apiCall {
        client.put(url.resolve("api/mcp/servers/${name.encodeURLPathPart()}/enabled")) {
            profile(profile)
            contentType(ContentType.Application.Json)
            setBody(EnabledToggle(enabled))
        }
    }.map { }

    /** Connects to the server, lists its tools and disconnects; a failed connection is still a success here, with `ok = false`. */
    suspend fun testMcpServer(url: GatewayUrl, profile: String?, name: String): ApiResult<McpTestResult> = apiCall {
        client.post(url.resolve("api/mcp/servers/${name.encodeURLPathPart()}/test")) { profile(profile) }
    }.map { it.body<McpTestResult>() }

    private fun HttpRequestBuilder.profile(profile: String?) {
        profile?.let { parameter("profile", it) }
    }
}

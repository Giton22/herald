package dev.hermeskotlin.core.journey

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

/**
 * One thing the agent learned: a skill it wrote or a memory it keeps (agent/learning_graph.py).
 * [timestamp] is epoch seconds, when the gateway knows it.
 */
@Serializable
data class JourneyNode(
    val id: String,
    val label: String,
    val kind: String = "skill",
    val timestamp: Long? = null,
    val category: String? = null,
    val useCount: Int = 0,
    val state: String? = null,
    val pinned: Boolean = false,
    /** The memory file a memory comes from (`MEMORY.md`, `USER.md`). */
    val memorySource: String? = null,
) {
    val isMemory: Boolean get() = kind == "memory"

    /** [label] without the bookkeeping comments memory files carry (`<!-- observed: … -->`). */
    val title: String
        get() = label.replace(HTML_COMMENT, " ").replace(WHITESPACE, " ").trim().removeSuffix(":").trim().ifEmpty { label }

    private companion object {
        val HTML_COMMENT = Regex("""<!--.*?(-->|$)""")
        val WHITESPACE = Regex("""\s+""")
    }
}

@Serializable
data class JourneyGraph(val nodes: List<JourneyNode> = emptyList()) {
    val skills: Int get() = nodes.count { !it.isMemory }
    val memories: Int get() = nodes.count { it.isMemory }
}

/** A node's current text: the skill's SKILL.md or the memory chunk. */
@Serializable
data class JourneyNodeDetail(
    val ok: Boolean = true,
    val kind: String? = null,
    val label: String? = null,
    val content: String? = null,
)

/** Desktop's star map data (`/api/learning/graph`, `/api/learning/node`), read-only here. */
class JourneyApi(private val client: HttpClient) {

    suspend fun graph(url: GatewayUrl, profile: String? = null): ApiResult<JourneyGraph> =
        apiCall {
            client.get(url.resolve("api/learning/graph")) { profile?.let { parameter("profile", it) } }
        }.map { it.body<JourneyGraph>() }

    suspend fun node(url: GatewayUrl, id: String, profile: String? = null): ApiResult<JourneyNodeDetail> =
        apiCall {
            client.get(url.resolve("api/learning/node")) {
                parameter("id", id)
                profile?.let { parameter("profile", it) }
            }
        }.map { it.body<JourneyNodeDetail>() }
}

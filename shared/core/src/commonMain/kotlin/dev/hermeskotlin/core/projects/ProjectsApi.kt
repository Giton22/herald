package dev.hermeskotlin.core.projects

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A project as Desktop's sidebar groups chats: one the user made ([isAuto] false), a git root or folder the
 * gateway found chats in ([isAuto]), or Home ([isNoProject]) for chats with no folder. [path] is the folder a
 * new chat in the project starts in; Home has none.
 */
data class Project(
    val id: String,
    val label: String,
    val path: String?,
    val sessionCount: Int,
    val isAuto: Boolean = false,
    val isNoProject: Boolean = false,
)

/**
 * The gateway's projects (`projects.*`, tui_gateway/methods_config.py and methods_projects.py), per profile.
 * Chats belong to a project by their working folder (`cwd`, `git_repo_root`); there is no event for a change,
 * so callers ask again when the list refreshes.
 */
class ProjectsApi(private val connection: GatewayConnection) {

    /**
     * `projects.tree`: every project that has chats, the busiest first, Home last. Projects the gateway only
     * discovered on disk, or that only old, cron or helper sessions used, are left out: their chip would
     * open an empty list.
     */
    suspend fun projects(profile: String?): List<Project> {
        val reply = client().request(
            "projects.tree",
            buildJsonObject {
                profile?.let { put("profile", it) }
                put("session_limit", SESSION_LIMIT)
            },
        ) as? JsonObject
        return parseProjects(reply?.get("projects"))
    }

    /** `projects.project_sessions`: the chats in project [id], newest first. */
    suspend fun sessions(profile: String?, id: String): List<SessionSummary> {
        val reply = client().request(
            "projects.project_sessions",
            buildJsonObject {
                profile?.let { put("profile", it) }
                put("project_id", id)
                // The same window as the tree, so a chip's count matches what it opens.
                put("session_limit", SESSION_LIMIT)
            },
        ) as? JsonObject
        return parseProjectSessions(reply?.get("project"))
    }

    private fun client(): JsonRpcClient = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    companion object {
        /** The id of Home, the bucket for chats with no project folder. */
        const val NO_PROJECT_ID = "__no_project__"

        /** The newest sessions the gateway groups; it reads 2000 by default, which is a lot for a phone to ask often. */
        const val SESSION_LIMIT = 500

        internal fun parseProjects(element: JsonElement?): List<Project> =
            (element as? JsonArray).orEmpty().mapNotNull { node ->
                val o = node as? JsonObject ?: return@mapNotNull null
                val id = o.text("id") ?: return@mapNotNull null
                // `sessionIds` are the chats the project really holds in the window; a discovered repo's
                // `sessionCount` counts all history (cron runs, helpers, years back) and opens on nothing.
                val ids = o["sessionIds"] as? JsonArray
                Project(
                    id = id,
                    label = o.text("label") ?: id,
                    path = o.text("path"),
                    sessionCount = ids?.size ?: (o["sessionCount"] as? JsonPrimitive)?.intOrNull ?: 0,
                    isAuto = (o["isAuto"] as? JsonPrimitive)?.booleanOrNull == true,
                    isNoProject = (o["isNoProject"] as? JsonPrimitive)?.booleanOrNull == true || id == NO_PROJECT_ID,
                )
            }
                .filter { it.sessionCount > 0 }
                .sortedWith(compareBy<Project> { it.isNoProject }.thenByDescending { it.sessionCount })

        /** Every lane's rows of a hydrated project node, once each, newest first. */
        internal fun parseProjectSessions(element: JsonElement?): List<SessionSummary> {
            val project = element as? JsonObject ?: return emptyList()
            val rows = (project["repos"] as? JsonArray).orEmpty()
                .flatMap { repo -> ((repo as? JsonObject)?.get("groups") as? JsonArray).orEmpty() }
                .flatMap { lane -> ((lane as? JsonObject)?.get("sessions") as? JsonArray).orEmpty() }
                .mapNotNull { row -> runCatching { HermesJson.decodeFromJsonElement(SessionSummary.serializer(), row) }.getOrNull() }
            return rows.distinctBy { it.id }.sortedByDescending { it.activityAt ?: 0.0 }
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

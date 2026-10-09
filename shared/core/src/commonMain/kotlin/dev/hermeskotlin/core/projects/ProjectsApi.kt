package dev.hermeskotlin.core.projects

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
) {
    /** A stored project (`projects.db`), which can be renamed and deleted; the others are only groupings. */
    val isUserMade: Boolean get() = !isAuto && !isNoProject
}

/** One folder's subfolders on the gateway; [more] when the gateway's page was full, so some may be missing. */
data class FolderListing(val folders: List<String>, val more: Boolean = false)

/**
 * The gateway's projects (`projects.*`, tui_gateway/methods_config.py and methods_projects.py), per profile.
 * Chats belong to a project by their working folder (`cwd`, `git_repo_root`); there is no event for a change,
 * so callers ask again when the list refreshes.
 */
class ProjectsApi(private val connection: GatewayConnection) {

    /**
     * `projects.tree`: every project that has chats or that the user made, the busiest first, Home last. Projects the gateway only
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

    /**
     * `projects.create`: a project named [name] over [folder] (its primary path; none for a project that only
     * groups chats later). The gateway refuses a folder another project already has, with a message saying so.
     * Answers the new project's id, or null when the reply leaves it out.
     */
    suspend fun create(profile: String?, name: String, folder: String?): String? {
        val reply = client().request(
            "projects.create",
            buildJsonObject {
                profile?.let { put("profile", it) }
                put("name", name.trim())
                folder?.trim()?.takeIf { it.isNotEmpty() }?.let { put("folders", JsonArray(listOf(JsonPrimitive(it)))) }
            },
        ) as? JsonObject
        return (reply?.get("project") as? JsonObject)?.text("id")
    }

    /** `projects.update`: renames project [id]. */
    suspend fun rename(profile: String?, id: String, name: String) {
        client().request(
            "projects.update",
            buildJsonObject {
                profile?.let { put("profile", it) }
                put("id", id)
                put("name", name.trim())
            },
        )
    }

    /** `projects.delete`: forgets project [id] and its folders. Its chats stay, and group by their folder again. */
    suspend fun delete(profile: String?, id: String) {
        client().request(
            "projects.delete",
            buildJsonObject {
                profile?.let { put("profile", it) }
                put("id", id)
            },
        )
    }

    /**
     * `complete.path`, as a folder picker: the folders in [dir] on the gateway's machine (`~/`, or a path ending
     * in `/`) whose names start with [prefix]. The gateway lists only the first [FOLDER_PAGE] entries, files and
     * folders mixed, dot-files first. So a full page with no [prefix] is asked again once per first character, a
     * few at a time, and the pages put together; with a [prefix], a full page is left for a longer one to narrow.
     */
    suspend fun folders(profile: String?, dir: String, prefix: String = ""): FolderListing {
        val first = folderPage(profile, dir, prefix)
        if (!first.more || prefix.isNotEmpty()) return first
        val gate = Semaphore(FOLDER_ASKS_AT_ONCE)
        val pages = coroutineScope {
            NAME_STARTS.map { c ->
                async {
                    gate.withPermit {
                        // One letter's page failing leaves the rest to show.
                        try {
                            folderPage(profile, dir, c.toString())
                        } catch (e: RpcException) {
                            FolderListing(emptyList())
                        }
                    }
                }
            }.awaitAll()
        }
        // Names starting with anything else (`@`, a space, a letter with an accent) weren't asked for.
        return FolderListing((first.folders + pages.flatMap { it.folders }).distinct().sortedBy { it.lowercase() }, more = true)
    }

    private suspend fun folderPage(profile: String?, dir: String, prefix: String): FolderListing {
        val reply = client().request(
            "complete.path",
            buildJsonObject {
                profile?.let { put("profile", it) }
                put("word", dir + prefix)
            },
        ) as? JsonObject
        return parseFolders(reply?.get("items"))
    }

    private fun client(): JsonRpcClient = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    companion object {
        /** The id of Home, the bucket for chats with no project folder. */
        const val NO_PROJECT_ID = "__no_project__"

        /** How many entries `complete.path` lists at most. */
        const val FOLDER_PAGE = 30

        /** What a folder name usually starts with; the gateway matches without case, so lowercase is enough. */
        private const val NAME_STARTS = "abcdefghijklmnopqrstuvwxyz0123456789_-"

        /** How many of those pages are asked for together; each is a directory read (a shell on a remote backend). */
        private const val FOLDER_ASKS_AT_ONCE = 4

        /** `complete.path` items: the folders by name (`display`, without its `/`), files left out. */
        internal fun parseFolders(element: JsonElement?): FolderListing {
            val items = (element as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val folders = items
                .filter { it.text("meta") == "dir" }
                .mapNotNull { it.text("display")?.removeSuffix("/")?.takeIf { name -> name.isNotEmpty() } }
            return FolderListing(folders, more = items.size >= FOLDER_PAGE)
        }

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
                    // Every node says `isAuto`; one that doesn't is taken as found, not made, so it never
                    // shows empty or offers Rename and Delete.
                    isAuto = (o["isAuto"] as? JsonPrimitive)?.booleanOrNull != false,
                    isNoProject = (o["isNoProject"] as? JsonPrimitive)?.booleanOrNull == true || id == NO_PROJECT_ID,
                )
            }
                // A project the user made shows with no chats yet, as on Desktop: it was made to start chats in.
                .filter { it.sessionCount > 0 || it.isUserMade }
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

package dev.hermeskotlin.core.projects

import dev.hermeskotlin.core.network.HermesJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectsApiTest {

    // Shaped like project_tree._project_node in hermes-agent v2026.9.24.
    private val tree = HermesJson.parseToJsonElement(
        """[
          {"id":"__no_project__","label":"Home","path":null,"isAuto":false,"isNoProject":true,"sessionCount":7,"repos":[]},
          {"id":"p1","label":"herald","path":"/srv/herald","color":"blue","isAuto":false,"isNoProject":false,"sessionCount":3,"repos":[]},
          {"id":"/srv/scratch","label":"scratch","path":"/srv/scratch","isAuto":true,"isNoProject":false,"sessionCount":12,"repos":[]},
          {"id":"/srv/empty","label":"empty","path":"/srv/empty","isAuto":true,"sessionCount":0,"repos":[]},
          {"id":"/srv/cron-only","label":"cron-only","path":"/srv/cron-only","isAuto":true,"sessionCount":14,"sessionIds":[],"repos":[]},
          {"id":"/srv/real","label":"real","path":"/srv/real","isAuto":true,"sessionCount":40,"sessionIds":["x","y"],"repos":[]},
          {"label":"no id"}
        ]""",
    )

    @Test
    fun projectsWithChatsComeBusiestFirstAndHomeLast() {
        val projects = ProjectsApi.parseProjects(tree)
        // A discovered repo counts all history; only the chats it holds (sessionIds) make a chip and its count.
        assertEquals(listOf("/srv/scratch", "p1", "/srv/real", "__no_project__"), projects.map { it.id })
        assertEquals(2, projects.first { it.id == "/srv/real" }.sessionCount)
        val home = projects.last()
        assertTrue(home.isNoProject)
        assertEquals(null, home.path)
        assertEquals("/srv/herald", projects[1].path)
        assertTrue(projects.first().isAuto)
    }

    @Test
    fun aProjectsChatsAreEveryLanesRowsOnceNewestFirst() {
        val project = HermesJson.parseToJsonElement(
            """{"id":"p1","label":"herald","repos":[
                {"id":"r1","groups":[
                  {"id":"main","sessions":[{"id":"a","title":"Old","last_active":100,"cwd":"/srv/herald"},{"id":"b","title":"New","last_active":300}]},
                  {"id":"wt","sessions":[{"id":"c","started_at":200},{"id":"a","title":"Old","last_active":100}]}
                ]},
                {"id":"r2","groups":[{"id":"x","sessions":[{"title":"no id"}]}]}
            ]}""",
        )
        assertEquals(listOf("b", "c", "a"), ProjectsApi.parseProjectSessions(project).map { it.id })
        assertEquals(emptyList(), ProjectsApi.parseProjectSessions(null))
    }
}

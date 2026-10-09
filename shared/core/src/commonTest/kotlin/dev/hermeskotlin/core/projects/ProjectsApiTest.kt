package dev.hermeskotlin.core.projects

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun aProjectTheUserMadeShowsBeforeItHasChats() {
        val projects = ProjectsApi.parseProjects(
            HermesJson.parseToJsonElement(
                """[
                  {"id":"p_new","label":"Fresh","path":"/srv/fresh","isAuto":false,"isNoProject":false,"sessionCount":0,"sessionIds":[]},
                  {"id":"/srv/auto","label":"auto","path":"/srv/auto","isAuto":true,"sessionCount":0,"sessionIds":[]},
                  {"id":"/srv/unsaid","label":"unsaid","path":"/srv/unsaid","sessionCount":0,"sessionIds":[]},
                  {"id":"/srv/busy","label":"busy","path":"/srv/busy","sessionIds":["a"]},
                  {"id":"__no_project__","label":"Home","isNoProject":true,"sessionCount":0}
                ]""",
            ),
        )
        // A node that doesn't say `isAuto` is taken as found: no empty chip, no Rename or Delete.
        assertEquals(listOf("busy", "Fresh"), projects.map { it.label })
        assertEquals(listOf(false, true), projects.map { it.isUserMade })
    }

    @Test
    fun createRenameAndDeleteSendDesktopsCalls() = runTest {
        val fake = FakeGateway(backgroundScope)
        fake.answer = { call ->
            when (call.method) {
                "projects.delete" -> """{"projects":[],"active_id":null}"""
                "projects.create" -> """{"project":{"id":"p_herald","name":"Herald","folders":[]}}"""
                else -> """{"project":null}"""
            }
        }
        val api = ProjectsApi(fake.start())

        assertEquals("p_herald", api.create("work", "  Herald  ", " /srv/herald "))
        val create = fake.sent("projects.create").single().params
        assertEquals("Herald", create["name"]?.jsonPrimitive?.content)
        assertEquals("work", create["profile"]?.jsonPrimitive?.content)
        assertEquals(listOf("/srv/herald"), create["folders"]?.jsonArray?.map { it.jsonPrimitive.content })

        api.create(null, "No folder", "  ")
        val bare = fake.sent("projects.create").last().params
        assertFalse("folders" in bare)
        assertNull(bare["profile"])

        api.rename(null, "p1", "Renamed ")
        val update = fake.sent("projects.update").single().params
        assertEquals("p1", update["id"]?.jsonPrimitive?.content)
        assertEquals("Renamed", update["name"]?.jsonPrimitive?.content)

        api.delete(null, "p1")
        assertEquals("p1", fake.sent("projects.delete").single().params["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun aRefusedFolderComesBackAsTheGatewaysMessage() = runTest {
        val fake = FakeGateway(backgroundScope)
        fake.answer = { call -> call.error(5063, "folder already belongs to project 'herald'") }
        val api = ProjectsApi(fake.start())
        val e = assertFailsWith<RpcException> { api.create(null, "Dup", "/srv/herald") }
        assertTrue(e.message.orEmpty().contains("already belongs"))
    }

    private val JsonObject.params get() = getValue("params").jsonObject
}

package dev.hermeskotlin.core.threads

import dev.hermeskotlin.core.chat.LastChat
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThreadsRepositoryTest {

    /** Everything sent, in order: `PATCH <id> <body>` for a REST change, the method name for a socket call. */
    private val sent = mutableListOf<String>()

    /** Every `profiles.configure` sent. */
    private val configured = mutableListOf<JsonObject>()

    /** The threads block `profiles.list` answers with, and its revision. */
    private var stored = """{"threads":[{"id":"t1","name":"Misc","sessionId":"old"},{"id":"t2","name":"Work","sessionId":"w","cwd":"/srv/work"}]}"""
    private var revision = 3

    private val lastChats = LastChatStore(InMemoryKeyValueStore())

    private suspend fun repository(
        scope: CoroutineScope,
        /** Answers a PATCH by its body; null is a plain success. */
        patch: (String) -> Pair<HttpStatusCode, String>? = { null },
        /** Answers every other socket call; null falls through to the defaults. */
        rpc: (FakeGateway.Call) -> String? = { null },
    ): ThreadsRepository {
        val gateway = FakeGateway(scope, reconnects = false)
        gateway.answer = { call ->
            sent += call.method
            rpc(call) ?: when (call.method) {
                "profiles.list" -> """{"profiles":[{"name":"default","is_default":true},{"name":"work","ui_meta":{"herald-threads":$stored},"ui_meta_revisions":{"herald-threads":$revision}}]}"""
                "profiles.configure" -> {
                    configured += call.params
                    """{"ok":true,"applied":{"ui_meta":true}}"""
                }
                "session.create" -> """{"session_id":"rt","stored_session_id":"fresh"}"""
                else -> "{}"
            }
        }
        gateway.http = { request ->
            when {
                request.method == HttpMethod.Patch -> {
                    val body = request.body.toByteArray().decodeToString()
                    sent += "PATCH ${request.url.encodedPath.substringAfterLast('/')} $body"
                    val (status, reply) = patch(body) ?: (HttpStatusCode.OK to "{}")
                    json(reply, status)
                }
                request.method == HttpMethod.Delete -> {
                    sent += "DELETE ${request.url.encodedPath.substringAfterLast('/')}"
                    json("{}")
                }
                request.url.encodedPath == "/api/profiles/active" -> json("""{"current":"work"}""")
                request.url.encodedPath == "/api/profiles" -> json("""{"profiles":[{"name":"default","is_default":true},{"name":"work"}]}""")
                else -> error("unexpected ${request.url}")
            }
        }
        val connection = gateway.start()
        return ThreadsRepository(ThreadsApi(connection), SessionsApi(gateway.client), ProfilesApi(gateway.client), lastChats) { seconds ->
            if (seconds) "2026-10-13 09:30:15" else "2026-10-13 09:30"
        }.also { it.bind(URL, "work") }
    }

    private fun savedThreads(): List<ChatThread> = ThreadMeta.parse(configured.last()["ui_meta"]!!.jsonObject[ThreadMeta.KEY])

    @Test
    fun theThreadsAreReadFromTheProfilesUiMeta() = runTest {
        val repo = repository(backgroundScope)
        repo.refresh(force = true)
        assertEquals(listOf("Misc", "Work"), repo.threads.value.map { it.name })
        assertEquals("/srv/work", repo.threads.value[1].cwd)
    }

    @Test
    fun theLaunchProfileIsReadAndWrittenUnderItsOwnName() = runTest {
        val repo = repository(backgroundScope)
        repo.bind(URL, null)
        repo.refresh(force = true)
        assertEquals(listOf("Misc", "Work"), repo.threads.value.map { it.name })
        repo.change { it.movedPast("t2", "t1") }
        assertEquals("work", configured.single()["name"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aWriteNamesTheRevisionItReadAndALostRaceReadsAgain() = runTest {
        var conflicts = 1
        val repo = repository(backgroundScope, rpc = { call ->
            if (call.method == "profiles.configure" && conflicts-- > 0) {
                configured += call.params
                // Another device added a thread meanwhile.
                stored = """{"threads":[{"id":"t1","name":"Misc","sessionId":"old"},{"id":"t2","name":"Work","sessionId":"w"},{"id":"t3","name":"Elsewhere","sessionId":"e"}]}"""
                revision = 4
                """{"ok":true,"applied":{"ui_meta":false,"ui_meta_conflicts":{"herald-threads":{"expected":3,"actual":4}}}}"""
            } else {
                null
            }
        })

        repo.change { it.renamed("t1", "Errands") }

        assertEquals(listOf(3, 4), configured.map { it["ui_meta_expected_revisions"]!!.jsonObject[ThreadMeta.KEY]!!.jsonPrimitive.content.toInt() })
        assertEquals(listOf("Errands", "Work", "Elsewhere"), savedThreads().map { it.name })
        assertEquals(listOf("Errands", "Work", "Elsewhere"), repo.threads.value.map { it.name })
    }

    @Test
    fun aRaceLostEveryTimeIsAnError() = runTest {
        val repo = repository(backgroundScope, rpc = { call ->
            if (call.method == "profiles.configure") """{"ok":true,"applied":{"ui_meta_conflicts":{"herald-threads":{"expected":3,"actual":9}}}}""" else null
        })
        assertFailsWith<Exception> { repo.change { it.movedPast("t1", "t2") } }
        assertEquals(4, sent.count { it == "profiles.configure" })
    }

    @Test
    fun makingAThreadAddsItAtTheEndAndPinsTheChat() = runTest {
        val repo = repository(backgroundScope)
        repo.makeThread(SessionSummary("chat", title = "Groceries", cwd = "/srv/home"))
        val added = savedThreads().last()
        assertEquals(Triple("Groceries", "chat", "/srv/home"), Triple(added.name, added.sessionId, added.cwd))
        assertEquals("""PATCH chat {"pinned":true,"profile":"work"}""", sent.last())
    }

    @Test
    fun renamingAThreadRetitlesItsChatToo() = runTest {
        val repo = repository(backgroundScope)
        repo.refresh(force = true)
        repo.noteRows(URL, "work", listOf(SessionSummary("tip", title = "Misc", pinned = true, lineageIds = listOf("old", "tip"))))

        repo.rename(repo.threads.value.first(), "Errands")

        assertEquals("""PATCH tip {"title":"Errands","profile":"work"}""", sent.first { it.startsWith("PATCH") })
        assertEquals("Errands", savedThreads().first().name)
    }

    @Test
    fun aTakenNameLeavesTheThreadAsItWas() = runTest {
        val repo = repository(backgroundScope, patch = {
            HttpStatusCode.BadRequest to """{"detail":"Title 'Errands' is already in use"}"""
        })
        repo.refresh(force = true)
        repo.noteRows(URL, "work", listOf(SessionSummary("tip", title = "Misc", pinned = true, lineageIds = listOf("old", "tip"))))

        assertFailsWith<ThreadActionException> { repo.rename(repo.threads.value.first(), "Errands") }

        assertEquals(emptyList(), configured)
        assertEquals("Misc", repo.threads.value.first().name)
    }

    @Test
    fun startingFreshFilesTheOldChatAwayBeforeMakingTheNewOne() = runTest {
        val repo = repository(backgroundScope)
        repo.refresh(force = true)
        // The thread's chat was compressed: the list shows it under its newest id.
        repo.noteRows(URL, "work", listOf(SessionSummary("tip", title = "Misc", pinned = true, lineageIds = listOf("old", "tip"))))

        val fresh = repo.startFresh(repo.threads.value.first())

        assertEquals(FreshChat("fresh", "Misc"), fresh)
        assertEquals(
            listOf(
                """PATCH tip {"title":"Misc · 2026-10-13 09:30","archived":true,"pinned":false,"profile":"work"}""",
                """PATCH old {"archived":true,"pinned":false,"profile":"work"}""",
                "session.create",
                "session.title",
                """PATCH fresh {"pinned":true,"profile":"work"}""",
                "profiles.list",
                "profiles.configure",
            ),
            sent.dropWhile { !it.startsWith("PATCH") },
        )
        assertEquals("fresh", savedThreads().first().sessionId)
    }

    @Test
    fun aNewChatRunsInTheThreadsFolder() = runTest {
        var cwd: String? = null
        val repo = repository(backgroundScope, rpc = { call ->
            if (call.method == "session.create") cwd = call.param("cwd")
            null
        })
        repo.refresh(force = true)
        repo.startFresh(repo.threads.value[1])
        assertEquals("/srv/work", cwd)
    }

    @Test
    fun aTakenArchiveTitleIsTriedAgainWithSeconds() = runTest {
        val repo = repository(backgroundScope, patch = { body ->
            if ("09:30\"" in body) HttpStatusCode.BadRequest to """{"detail":"Title 'Misc · 2026-10-13 09:30' is already in use"}""" else null
        })
        repo.refresh(force = true)
        repo.startFresh(repo.threads.value.first())
        assertTrue(sent.any { "Misc · 2026-10-13 09:30:15" in it })
    }

    @Test
    fun aTakenNameGetsANumber() = runTest {
        val titles = mutableListOf<String>()
        val repo = repository(backgroundScope, rpc = { call ->
            if (call.method == "session.title") {
                titles += call.param("title").orEmpty()
                if (titles.size == 1) call.error(4022, "Title 'Misc' is already in use") else null
            } else {
                null
            }
        })
        repo.refresh(force = true)
        val fresh = repo.startFresh(repo.threads.value.first())
        assertEquals(listOf("Misc", "Misc (2)"), titles)
        assertEquals("Misc (2)", fresh.title)
        // The chat made for the taken name was let go of.
        assertTrue("session.delete" in sent)
    }

    @Test
    fun aNewChatThatCantBeMadePutsTheOldOneBack() = runTest {
        val repo = repository(backgroundScope, rpc = { call -> if (call.method == "session.create") "error:5000" else null })
        repo.refresh(force = true)
        repo.noteRows(URL, "work", listOf(SessionSummary("old", title = "Misc", pinned = true)))

        assertFailsWith<ThreadActionException> { repo.startFresh(repo.threads.value.first()) }

        val patches = sent.filter { it.startsWith("PATCH") }
        assertEquals(
            listOf(
                """PATCH old {"title":"Misc · 2026-10-13 09:30","archived":true,"pinned":false,"profile":"work"}""",
                """PATCH old {"title":"Misc","archived":false,"pinned":true,"profile":"work"}""",
            ),
            patches,
        )
        assertTrue(configured.isEmpty())
    }

    @Test
    fun theNextLaunchReopensTheNewChatNotTheFiledAwayOne() = runTest {
        val repo = repository(backgroundScope)
        repo.refresh(force = true)
        lastChats.set(URL, LastChat("old", "Misc"), "work")

        repo.startFresh(repo.threads.value.first())

        assertEquals(LastChat("fresh", "Misc"), lastChats.get(URL, "work"))
    }

    @Test
    fun aLaunchChatElsewhereIsLeftAlone() = runTest {
        val repo = repository(backgroundScope)
        repo.refresh(force = true)
        lastChats.set(URL, LastChat("other", "Groceries"), "work")

        repo.startFresh(repo.threads.value.first())

        assertEquals("other", lastChats.get(URL, "work")?.sessionId)
    }

    @Test
    fun aBlankNameChangesNeitherTheThreadNorItsChat() = runTest {
        val repo = repository(backgroundScope)
        repo.refresh(force = true)
        repo.noteRows(URL, "work", listOf(SessionSummary("old", title = "Misc", pinned = true)))

        repo.rename(repo.threads.value.first(), "  ")

        assertTrue(sent.none { it.startsWith("PATCH") })
        assertTrue(configured.isEmpty())
    }

    @Test
    fun aNewChatThatCantBePinnedIsDeletedAndTheOldOneComesBack() = runTest {
        val repo = repository(backgroundScope, patch = { body ->
            if (body == """{"pinned":true,"profile":"work"}""") HttpStatusCode.InternalServerError to """{"detail":"Store is busy"}""" else null
        })
        repo.refresh(force = true)
        repo.noteRows(URL, "work", listOf(SessionSummary("old", title = "Misc", pinned = true)))

        assertFailsWith<ThreadActionException> { repo.startFresh(repo.threads.value.first()) }

        // Its runtime let go of, then deleted before the old chat takes its title back, which the new one held.
        assertEquals(
            listOf("session.close", "DELETE fresh", """PATCH old {"title":"Misc","archived":false,"pinned":true,"profile":"work"}"""),
            sent.filter { it == "session.close" || it.startsWith("DELETE") || "\"archived\":false" in it },
        )
        assertTrue(configured.isEmpty())
    }

    @Test
    fun aSaveWhoseReplyWasLostStillCounts() = runTest {
        val repo = repository(backgroundScope, rpc = { call ->
            if (call.method == "profiles.configure") {
                // The write lands; its reply doesn't.
                configured += call.params
                stored = """{"threads":[{"id":"t1","name":"Misc","sessionId":"fresh"},{"id":"t2","name":"Work","sessionId":"w"}]}"""
                revision = 4
                "error:5000"
            } else {
                null
            }
        })
        repo.refresh(force = true)

        val fresh = repo.startFresh(repo.threads.value.first())

        assertEquals("fresh", fresh.sessionId)
        assertTrue(sent.none { it.startsWith("DELETE") || "\"archived\":false" in it })
        assertEquals("fresh", repo.threads.value.first().sessionId)
    }

    @Test
    fun aThreadThatCantBePointedAtItsNewChatKeepsItsOldOne() = runTest {
        val repo = repository(backgroundScope, rpc = { call ->
            if (call.method == "profiles.configure") """{"ok":true,"applied":{"ui_meta_conflicts":{"herald-threads":{"expected":3,"actual":9}}}}""" else null
        })
        repo.refresh(force = true)
        repo.noteRows(URL, "work", listOf(SessionSummary("old", title = "Misc", pinned = true)))

        assertFailsWith<ThreadActionException> { repo.startFresh(repo.threads.value.first()) }

        assertTrue("DELETE fresh" in sent)
        assertEquals("""PATCH old {"title":"Misc","archived":false,"pinned":true,"profile":"work"}""", sent.last { it.startsWith("PATCH") })
        assertEquals("old", repo.threads.value.first().sessionId)
    }

    @Test
    fun anOldChatThatCantBePutBackIsNamedInTheMessage() = runTest {
        val repo = repository(
            backgroundScope,
            patch = { body -> if ("\"archived\":false" in body) HttpStatusCode.InternalServerError to """{"detail":"Store is busy"}""" else null },
            rpc = { call -> if (call.method == "session.create") "error:5000" else null },
        )
        repo.refresh(force = true)

        val error = assertFailsWith<ThreadActionException> { repo.startFresh(repo.threads.value.first()) }

        assertTrue("Misc · 2026-10-13 09:30" in error.message.orEmpty(), error.message)
    }

    @Test
    fun rowsListedForAnotherProfileAreIgnored() = runTest {
        val repo = repository(backgroundScope)
        val rows = listOf(SessionSummary("old", title = "Misc"))
        repo.noteRows(URL, "default", rows)
        assertEquals(emptyList(), repo.rows.value)
        repo.noteRows(URL, "work", rows)
        assertEquals(rows, repo.rows.value)
    }

    @Test
    fun anOldChatThatCantBeFiledAwayStopsBeforeAnythingIsMade() = runTest {
        val repo = repository(backgroundScope, patch = { HttpStatusCode.InternalServerError to """{"detail":"Store is busy"}""" })
        repo.refresh(force = true)
        assertFailsWith<ThreadActionException> { repo.startFresh(repo.threads.value.first()) }
        assertTrue("session.create" !in sent)
    }

    @Test
    fun aChatIsKnownAsAThreadsByItsIdOrItsLineage() {
        val threads = listOf(ChatThread("t1", "Misc", "old"))
        val rows = listOf(SessionSummary("tip", lineageIds = listOf("old", "tip")))
        assertEquals("t1", ThreadsRepository.threadFor("old", threads, rows)?.id)
        assertEquals("t1", ThreadsRepository.threadFor("tip", threads, rows)?.id)
        assertEquals(null, ThreadsRepository.threadFor("other", threads, rows))
        assertEquals(null, ThreadsRepository.threadFor(null, threads, rows))
    }

    private companion object {
        val URL = GatewayUrl.parse("https://hermes.example.ts.net")
    }
}

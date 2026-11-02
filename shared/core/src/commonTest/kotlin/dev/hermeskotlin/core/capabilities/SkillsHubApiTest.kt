package dev.hermeskotlin.core.capabilities

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillsHubApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun sourcesCarryFeaturedAndInstalledForTheProfile() = runTest {
        var query: String? = null
        val api = SkillsHubApi(createHttpClient(MockEngine { request ->
            query = request.url.encodedQuery
            respond(
                """{"sources":[{"id":"official","label":"Official"}],"index_available":true,"featured":[{"name":"ascii-art","description":"ASCII art","source":"official","identifier":"official/creative/ascii-art","trust_level":"builtin","repo":null,"tags":["art"]}],"installed":{"official/creative/ascii-art":{"name":"ascii-art","trust_level":"builtin","scan_verdict":"safe"}}}""",
                HttpStatusCode.OK, json,
            )
        }))

        val sources = assertIs<ApiResult.Success<HubSources>>(api.sources(url, "ruby")).value

        assertTrue(query!!.contains("profile=ruby"))
        assertTrue(sources.featured.single().isOfficial)
        assertEquals(setOf("official/creative/ascii-art"), sources.installed.keys)
    }

    @Test
    fun aSlowReadMayBeSilentAsLongAsItMayTake() = runTest {
        val timeouts = mutableMapOf<String, HttpTimeoutConfig?>()
        val api = SkillsHubApi(createHttpClient(MockEngine { request ->
            timeouts[request.url.encodedPath] = request.getCapabilityOrNull(HttpTimeoutCapability)
            respond("{}", HttpStatusCode.OK, json)
        }))

        api.sources(url, null)
        api.official(url, null)
        api.search(url, null, "art")
        api.preview(url, null, "official/creative/ascii-art")
        api.scan(url, null, "official/creative/ascii-art")

        assertEquals(5, timeouts.size)
        for ((path, timeout) in timeouts) {
            val request = assertNotNull(timeout?.requestTimeoutMillis, path)
            val socket = assertNotNull(timeout?.socketTimeoutMillis, path)
            assertTrue(socket >= request, "$path: silence cut off after $socket ms of $request ms")
        }
    }

    @Test
    fun officialUnwrapsSkillsWithCategory() = runTest {
        val api = SkillsHubApi(createHttpClient(MockEngine {
            respond("""{"skills":[{"name":"x","identifier":"official/research/x","category":"research","installed":true}]}""", HttpStatusCode.OK, json)
        }))

        val skill = assertIs<ApiResult.Success<List<HubSkill>>>(api.official(url, null)).value.single()

        assertEquals("research", skill.category)
        assertTrue(skill.installed)
    }

    @Test
    fun searchSendsQueryAndReadsTimeouts() = runTest {
        var query: String? = null
        val api = SkillsHubApi(createHttpClient(MockEngine { request ->
            query = request.url.encodedQuery
            respond(
                """{"results":[{"name":"pdf","source":"clawhub","identifier":"clawhub/org/pdf","trust_level":"community"}],"source_counts":{},"timed_out":["github"],"installed":{}}""",
                HttpStatusCode.OK, json,
            )
        }))

        val search = assertIs<ApiResult.Success<HubSearch>>(api.search(url, null, "pdf")).value

        val params = query!!.split("&")
        assertTrue("q=pdf" in params && "source=all" in params && "limit=20" in params)
        assertEquals(listOf("github"), search.timedOut)
        assertFalse(search.results.single().isOfficial)
    }

    @Test
    fun previewAndScanDecodeNullsAsDefaults() = runTest {
        val queries = mutableListOf<String>()
        val api = SkillsHubApi(createHttpClient(MockEngine { request ->
            queries += request.url.encodedQuery
            if (request.url.encodedPath.endsWith("preview")) {
                respond("""{"name":"pdf","identifier":"clawhub/org/pdf","repo":null,"tags":null,"files":null}""", HttpStatusCode.OK, json)
            } else {
                respond(
                    """{"verdict":"caution","policy":"ask","findings":[{"severity":"high","category":"exfiltration","file":"run.sh","line":3,"description":"curl to remote host"}]}""",
                    HttpStatusCode.OK, json,
                )
            }
        }))

        val preview = assertIs<ApiResult.Success<HubPreview>>(api.preview(url, "ruby", "clawhub/org/pdf")).value
        assertEquals(emptyList(), preview.tags)
        assertEquals(emptyList(), preview.files)

        val scan = assertIs<ApiResult.Success<HubScan>>(api.scan(url, "ruby", "clawhub/org/pdf")).value
        assertEquals("ask", scan.policy)
        assertEquals(3, scan.findings.single().line)
        assertTrue(queries.all { "profile=ruby" in it })
    }

    @Test
    fun aNullNameDecodesAsBlank() = runTest {
        val api = SkillsHubApi(createHttpClient(MockEngine {
            respond("""{"skills":[{"name":null,"identifier":"official/x/y"}]}""", HttpStatusCode.OK, json)
        }))
        assertEquals("", assertIs<ApiResult.Success<List<HubSkill>>>(api.official(url, null)).value.single().name)
    }

    @Test
    fun missingRouteIsAFailureNotACrash() = runTest {
        val api = SkillsHubApi(createHttpClient(MockEngine {
            respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json)
        }))

        val failed = assertIs<ApiResult.Failed>(api.sources(url, null))
        assertEquals(404, failed.status)
    }

    private val startOk = """{"name":"skills-install-x","ok":true,"pid":7}"""
    private fun status(running: Boolean, exit: Int?, vararg lines: String) =
        """{"name":"skills-install-x","running":$running,"exit_code":${exit ?: "null"},"pid":null,"lines":[${lines.joinToString(",") { "\"" + it + "\"" }}]}"""

    /** Start answers [startBody]; each status read answers the next of [statuses] (the last repeats). */
    private fun actionApi(startBody: String, vararg statuses: Pair<HttpStatusCode, String>, calls: MutableList<String> = mutableListOf()) =
        SkillsHubApi(createHttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = if (request.method == HttpMethod.Post) request.body.toByteArray().decodeToString() else ""
            calls += "${request.method.value} $path ${request.url.encodedQuery} $body".trim()
            if (path.endsWith("/status")) {
                val n = calls.count { it.contains("/status") } - 1
                val (code, text) = statuses[minOf(n, statuses.size - 1)]
                respond(text, code, json)
            } else respond(startBody, HttpStatusCode.OK, json)
        }), pollIntervalMs = 0)

    private suspend fun finish(vararg statuses: Pair<HttpStatusCode, String>) =
        assertIs<ApiResult.Success<HubActionResult>>(actionApi(startOk, *statuses).install(url, null, "org/x")).value

    @Test
    fun installPollsUntilDoneAndSendsProfile() = runTest {
        val calls = mutableListOf<String>()
        val api = actionApi(startOk, HttpStatusCode.OK to status(true, null), HttpStatusCode.OK to status(false, 0, "Installed: x"), calls = calls)

        val result = assertIs<ApiResult.Success<HubActionResult>>(api.install(url, "ruby", "org/x")).value

        assertEquals(HubActionResult.Done(listOf("Installed: x")), result)
        val start = calls.first()
        assertTrue(start.startsWith("POST /api/skills/hub/install") && "\"identifier\":\"org/x\"" in start && "\"profile\":\"ruby\"" in start, start)
        assertEquals(2, calls.count { "/status" in it })
        assertTrue(calls.last().contains("/api/actions/skills-install-x/status") && calls.last().contains("lines=200") && calls.last().contains("profile=ruby"), calls.last())
    }

    @Test
    fun blockedInstallIsRecognised() = runTest {
        val result = finish(HttpStatusCode.OK to status(false, 1, "scanning", "Not installed: the security scan found 2 high-risk pattern(s) in 'org/evil' (listed above)."))
        assertEquals(2, assertIs<HubActionResult.Blocked>(result).findings)
    }

    @Test
    fun legacyBlockMessageIsRecognised() = runTest {
        val result = finish(HttpStatusCode.OK to status(false, 1, "Installation blocked: Blocked (community source + dangerous verdict, 3 findings)"))
        assertEquals(3, assertIs<HubActionResult.Blocked>(result).findings)
    }

    @Test
    fun failedActionKeepsTheLogTail() = runTest {
        assertEquals(HubActionResult.Failed(listOf("b", "c", "d")), finish(HttpStatusCode.OK to status(false, 1, "a", "b", "c", "d")))
    }

    @Test
    fun refusedStartIsAFailure() = runTest {
        val calls = mutableListOf<String>()
        val api = actionApi("""{"name":"","ok":false,"pid":null,"error":"identifier is required"}""", calls = calls)

        val result = assertIs<ApiResult.Success<HubActionResult>>(api.install(url, null, "")).value

        assertEquals(HubActionResult.Failed(listOf("identifier is required")), result)
        assertEquals(0, calls.count { "/status" in it })
    }

    @Test
    fun statusGoneMidPollEndsAsFailed() = runTest {
        val result = finish(HttpStatusCode.NotFound to """{"detail":"Not Found"}""")
        assertEquals(HubActionResult.Failed(listOf("Lost track of the install on the gateway.")), result)
    }

    @Test
    fun aBriefOutageDuringPollingIsRiddenOut() = runTest {
        val down = HttpStatusCode.ServiceUnavailable to """{"detail":"down"}"""
        val result = finish(down, down, HttpStatusCode.OK to status(false, 0, "Installed: x"))
        assertEquals(HubActionResult.Done(listOf("Installed: x")), result)
    }

    @Test
    fun aLastingOutageEndsThePolling() = runTest {
        val calls = mutableListOf<String>()
        val api = actionApi(startOk, HttpStatusCode.ServiceUnavailable to """{"detail":"down"}""", calls = calls)

        assertIs<ApiResult.Unavailable>(api.install(url, null, "org/x"))

        assertEquals(3, calls.count { "/status" in it })
    }

    @Test
    fun lostTrackNamesTheAction() = runTest {
        val api = actionApi(startOk, HttpStatusCode.NotFound to """{"detail":"Not Found"}""")
        val result = assertIs<ApiResult.Success<HubActionResult>>(api.uninstall(url, null, "x")).value
        assertEquals(HubActionResult.Failed(listOf("Lost track of the removal on the gateway.")), result)
    }

    @Test
    fun anOkStartWithoutANameIsAFailure() = runTest {
        val calls = mutableListOf<String>()
        val api = actionApi("""{"ok":true}""", calls = calls)
        val result = assertIs<ApiResult.Success<HubActionResult>>(api.install(url, null, "org/x")).value
        assertEquals(HubActionResult.Failed(listOf("The gateway didn't name the action.")), result)
        assertEquals(0, calls.count { "/status" in it })
    }

    @Test
    fun uninstallAndUpdateHitTheirRoutes() = runTest {
        val calls = mutableListOf<String>()
        val api = actionApi(startOk, HttpStatusCode.OK to status(false, 0), calls = calls)

        api.uninstall(url, null, "x")
        api.updateAll(url, null)

        val posts = calls.filter { it.startsWith("POST") }
        assertTrue(posts[0].startsWith("POST /api/skills/hub/uninstall") && "\"name\":\"x\"" in posts[0], posts[0])
        assertTrue(posts[1].startsWith("POST /api/skills/hub/update"), posts[1])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingStopsPolling() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            var statusCalls = 0
            // On the test dispatcher, so virtual time governs both the engine and the poll delay.
            val config = MockEngineConfig()
            config.dispatcher = dispatcher
            config.addHandler { request ->
                if (request.url.encodedPath.endsWith("/status")) {
                    statusCalls++
                    respond(status(true, null), HttpStatusCode.OK, json)
                } else respond(startOk, HttpStatusCode.OK, json)
            }
            val api = SkillsHubApi(createHttpClient(MockEngine(config)), pollIntervalMs = 1_000)

            val job = launch { api.install(url, null, "org/x") }
            runCurrent()
            assertEquals(1, statusCalls)
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(2, statusCalls)
            job.cancel()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, statusCalls)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anActionThatNeverEndsStopsBeingPolled() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            var statusCalls = 0
            val config = MockEngineConfig()
            config.dispatcher = dispatcher
            config.addHandler { request ->
                if (request.url.encodedPath.endsWith("/status")) {
                    statusCalls++
                    respond(status(true, null), HttpStatusCode.OK, json)
                } else respond(startOk, HttpStatusCode.OK, json)
            }
            val api = SkillsHubApi(createHttpClient(MockEngine(config)), pollIntervalMs = 1_000, actionTimeoutMs = 5_000)

            val result = assertIs<ApiResult.Success<HubActionResult>>(api.install(url, null, "org/x")).value

            assertEquals(HubActionResult.StillRunning(listOf("The install is still running on the gateway. Check back later.")), result)
            val calls = statusCalls
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(calls, statusCalls)
        }
    }

    @Test
    fun aPreviewsBodyLeavesOutTheFrontMatter() {
        val md = "---\nname: 1password\ndescription: Set up op CLI\ntags: [security, cli]\n---\n\n# 1Password CLI\n\nUse this skill when…\n"
        assertEquals("# 1Password CLI\n\nUse this skill when…\n", HubPreview(skillMd = md).body)
    }

    @Test
    fun aPreviewWithoutFrontMatterIsShownWhole() {
        assertEquals("# notes\n\n---\n\nmore", HubPreview(skillMd = "# notes\n\n---\n\nmore").body)
        // An opening fence that never closes isn't front matter.
        assertEquals("---\nname: x\n", HubPreview(skillMd = "---\nname: x\n").body)
    }
}

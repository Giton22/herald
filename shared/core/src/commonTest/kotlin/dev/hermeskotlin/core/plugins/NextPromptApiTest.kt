package dev.hermeskotlin.core.plugins

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NextPromptApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(engine: MockEngine) = createHttpClient(engine).let { NextPromptApi(it, PluginsApi(it)) }

    @Test
    fun readsTheChatsSuggestionWithItsProfile() = runTest {
        var request: String? = null
        val api = api(MockEngine {
            request = "${it.method.value} ${it.url.encodedPath}?${it.url.encodedQuery}"
            respond(
                """{"suggestion":{"text":"Commit the parser fix","session_id":"s1","timestamp":1790000000.5},"error":null}""",
                HttpStatusCode.OK, json,
            )
        })

        val state = assertIs<ApiResult.Success<NextPromptState>>(api.state(url, "s1", "work")).value

        assertEquals("GET /api/plugins/next-prompt/suggestion?session_id=s1&profile=work", request)
        assertEquals(NextPrompt("Commit the parser fix", "s1", 1790000000.5), state.suggestion)
        assertNull(state.error)
    }

    @Test
    fun readsNoSuggestionAndAFailure() = runTest {
        val empty = api(MockEngine { respond("""{"suggestion":null,"error":null}""", HttpStatusCode.OK, json) })
        assertEquals(NextPromptState(), assertIs<ApiResult.Success<NextPromptState>>(empty.state(url, "s1", null)).value)

        val failed = api(MockEngine {
            respond("""{"suggestion":null,"error":{"kind":"rate_limit","session_id":"s1","timestamp":1.0}}""", HttpStatusCode.OK, json)
        })
        val state = assertIs<ApiResult.Success<NextPromptState>>(failed.state(url, "s1", null)).value
        assertNull(state.suggestion)
        assertEquals("rate_limit", state.error?.kind)
    }

    @Test
    fun dismissPostsTheChat() = runTest {
        var request: String? = null
        val api = api(MockEngine {
            request = "${it.method.value} ${it.url.encodedPath}?${it.url.encodedQuery}"
            respond("""{"ok":true}""", HttpStatusCode.OK, json)
        })

        assertIs<ApiResult.Success<Unit>>(api.dismiss(url, "s1", null))
        assertEquals("${HttpMethod.Post.value} /api/plugins/next-prompt/dismiss?session_id=s1", request)
    }

    @Test
    fun installedWhenTheGatewayListsThePluginEvenWithItsTabHidden() = runTest {
        val with = api(MockEngine {
            respond(
                """[{"name":"example-board","tab":{"path":"/example-board"}},
                    {"name":"next-prompt","label":"Next Prompt","version":"0.3.1","tab":{"path":"/next-prompt","position":"end","hidden":true}}]""",
                HttpStatusCode.OK, json,
            )
        })
        assertEquals(true, assertIs<ApiResult.Success<Boolean>>(with.installed(url, null)).value)

        val without = api(MockEngine { respond("""[{"name":"example-board"}]""", HttpStatusCode.OK, json) })
        assertEquals(false, assertIs<ApiResult.Success<Boolean>>(without.installed(url, null)).value)
    }

    /**
     * A gateway without the plugin listed. [toggle] answers the toggle (`error:` turns it down) and, when
     * [toggleLists], puts it on the list; an install always does.
     */
    private class Installing(val gateway: FakeGateway, val toggle: String, val toggleLists: Boolean) {
        var listed = false
        val rescans = mutableListOf<String?>()
        val api = NextPromptApi(gateway.client, PluginsApi(gateway.client), gateway.connection)

        init {
            gateway.answer = { call ->
                when (call.param("action")) {
                    "toggle" -> toggle.also { if (toggleLists) listed = true }
                    "install" -> """{"ok":true}""".also { listed = true }
                    else -> "{}"
                }
            }
            gateway.http = { request ->
                when (request.url.encodedPath) {
                    "/api/dashboard/plugins/rescan" -> {
                        rescans += request.url.parameters["profile"]
                        json("""{"ok":true,"count":1}""")
                    }
                    "/api/dashboard/plugins" -> json(if (listed) """[{"name":"next-prompt","version":"0.3.1"}]""" else "[]")
                    else -> error("Unexpected ${request.url}")
                }
            }
        }

        fun actions() = gateway.sent("plugins.manage").map { it.param("action") }
    }

    @Test
    fun aSwitchedOffCopyIsOnlySwitchedOn() = runTest {
        val gateway = FakeGateway(backgroundScope)
        val setup = Installing(gateway, toggle = """{"ok":true}""", toggleLists = true)
        gateway.start()

        setup.api.install(url, "work")

        assertEquals(listOf<String?>("toggle"), setup.actions())
        assertEquals("work", gateway.sent("plugins.manage").single().param("profile"))
        assertEquals(listOf<String?>("work"), setup.rescans)
    }

    @Test
    fun aRefusedToggleInstallsAtThePinnedCommit() = runTest {
        val gateway = FakeGateway(backgroundScope)
        val setup = Installing(gateway, toggle = "error:4004", toggleLists = false)
        gateway.start()

        setup.api.install(url, null)

        assertEquals(listOf<String?>("toggle", "install"), setup.actions())
        val install = gateway.sent("plugins.manage").last()
        assertEquals(NextPromptApi.PLUGIN_REPO, install.param("identifier"))
        assertEquals(NextPromptApi.PLUGIN_REF, install.param("ref"))
        assertNull(install.param("profile"))
        assertEquals(true, assertIs<ApiResult.Success<Boolean>>(setup.api.installed(url, null)).value)
    }

    @Test
    fun aToggleThatSaysYesForAMissingPluginStillInstalls() = runTest {
        val gateway = FakeGateway(backgroundScope)
        val setup = Installing(gateway, toggle = """{"ok":true}""", toggleLists = false)
        gateway.start()

        setup.api.install(url, null)

        assertEquals(listOf<String?>("toggle", "install"), setup.actions())
        assertEquals(2, setup.rescans.size)
    }

    @Test
    fun servedIsFalseOnlyForAMissingRoute() = runTest {
        val notMounted = api(MockEngine { respond("""{"detail":"No such API endpoint"}""", HttpStatusCode.NotFound, json) })
        assertEquals(false, notMounted.served(url, null))

        val down = api(MockEngine { respond("", HttpStatusCode.BadGateway) })
        assertEquals(true, down.served(url, null))
    }
}

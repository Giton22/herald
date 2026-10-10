package dev.hermeskotlin.core.profiles

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.MACHINE_SOURCES
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProfilesApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun rosterPutsTheDefaultFirstAndNamesTheLaunchProfile() = runTest {
        val api = ProfilesApi(
            createHttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/profiles/active" -> respond("""{"active":"default","current":"work"}""", HttpStatusCode.OK, json)
                        else -> respond(
                            """{"profiles":[
                               {"name":"work","path":"/h/profiles/work","is_default":false,"display_name":"Work bot","skill_count":3},
                               {"name":"default","path":"/h","is_default":true,"display_name":"","model":"claude","gateway_running":true}]}""",
                            HttpStatusCode.OK, json,
                        )
                    }
                },
            ),
        )

        val roster = assertIs<ApiResult.Success<ProfileRoster>>(api.roster(url)).value

        assertEquals(listOf("default", "work"), roster.profiles.map { it.name })
        assertEquals(listOf("default", "Work bot"), roster.profiles.map { it.label })
        assertEquals("work", roster.launch)
    }

    @Test
    fun rosterFallsBackToDefaultWhenTheActiveRouteFails() = runTest {
        val api = ProfilesApi(
            createHttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath == "/api/profiles/active") respond("", HttpStatusCode.NotFound)
                    else respond("""{"profiles":[{"name":"default","is_default":true}]}""", HttpStatusCode.OK, json)
                },
            ),
        )

        assertEquals("default", assertIs<ApiResult.Success<ProfileRoster>>(api.roster(url)).value.launch)
    }

    @Test
    fun sessionCallsNameTheProfileOnlyWhenOneIsPicked() = runTest {
        val queries = mutableListOf<String>()
        val api = SessionsApi(
            createHttpClient(
                MockEngine { request ->
                    queries += request.url.encodedQuery
                    respond("""{"sessions":[],"total":0}""", HttpStatusCode.OK, json)
                },
            ),
        )

        api.list(url, profile = "work")
        api.list(url)

        val excludeSources = MACHINE_SOURCES.joinToString("%2C")
        assertEquals("profile=work&limit=50&offset=0&archived=exclude&order=recent&exclude_sources=$excludeSources", queries[0])
        assertEquals("limit=50&offset=0&archived=exclude&order=recent&exclude_sources=$excludeSources", queries[1])
    }

    @Test
    fun storeKeepsOnePickPerGateway() = runTest {
        val store = ProfileStore(InMemoryKeyValueStore())
        val other = GatewayUrl.parse("https://other.example.ts.net")

        store.set(url, "work")

        assertEquals("work", store.get(url))
        assertNull(store.get(other))
        store.set(url, null)
        assertNull(store.get(url))
    }
}

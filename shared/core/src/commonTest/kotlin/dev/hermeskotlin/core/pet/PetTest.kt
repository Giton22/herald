package dev.hermeskotlin.core.pet

import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.journey.JourneyApi
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
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

class PetTest {

    private fun sprite(framesByState: Map<String, Int> = emptyMap(), rows: List<String> = PetSprite.DEFAULT_ROWS) = PetSprite(
        slug = "boba", displayName = "Boba", sheet = ByteArray(0), revision = "r1",
        frameW = 192, frameH = 208, loopMs = 1100, scale = 0.33,
        framesPerState = 6, framesByState = framesByState, framesByRow = emptyMap(), stateRows = rows,
    )

    @Test
    fun statesFindTheirRowsAndFallBackToIdle() {
        assertEquals(0 to 6, sprite().rowFor(PetState.Idle))
        assertEquals(3 to 6, sprite().rowFor(PetState.Wave))
        assertEquals(7 to 6, sprite().rowFor(PetState.Run))
        // A ragged sheet with no review frames shows idle rather than blank cells.
        assertEquals(0 to 4, sprite(mapOf("review" to 0, "idle" to 4)).rowFor(PetState.Review))
    }

    @Test
    fun posesFollowDesktopsPriorities() {
        assertEquals(PetState.Failed, petStateOf(failed = true, justFinished = true, awaitingInput = true, toolRunning = true, reasoning = true, busy = true))
        assertEquals(PetState.Waiting, petStateOf(failed = false, justFinished = false, awaitingInput = true, toolRunning = true, reasoning = false, busy = true))
        assertEquals(PetState.Review, petStateOf(failed = false, justFinished = false, awaitingInput = false, toolRunning = false, reasoning = true, busy = true))
        // A stale tool flag doesn't keep the pet running once the turn is over.
        assertEquals(PetState.Idle, petStateOf(failed = false, justFinished = false, awaitingInput = false, toolRunning = true, reasoning = false, busy = false))
    }

    @Test
    fun journeyReadsTheLearningGraph() = runTest {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = createHttpClient(
            MockEngine { request ->
                assertEquals("work", request.url.parameters["profile"])
                respond(
                    """{"nodes":[{"id":"deploy","label":"deploy","kind":"skill","timestamp":1759000000,"category":"devops","useCount":3,"extra":1},
                    {"id":"memory:MEMORY.md:0","label":"Prefers short answers","kind":"memory","timestamp":null}],"stats":{}}""",
                    HttpStatusCode.OK, json,
                )
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val graph = assertIs<ApiResult.Success<*>>(JourneyApi(client).graph(GatewayUrl.parse("https://h.ts.net"), "work")).value
        graph as dev.hermeskotlin.core.journey.JourneyGraph
        assertEquals(1, graph.skills)
        assertEquals(1, graph.memories)
        assertEquals(3, graph.nodes.first().useCount)
        val noted = dev.hermeskotlin.core.journey.JourneyNode("m", "Directives: <!-- observed: 2026-09-25 | status: active -->", "memory")
        assertEquals("Directives", noted.title)
    }
}

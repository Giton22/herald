package dev.hermeskotlin.core.cron

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.SessionSummary
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CronApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun jobsParseScheduleStateAndTimes() = runTest {
        var query: String? = null
        val api = CronApi(createHttpClient(MockEngine { request ->
            query = request.url.encodedQuery
            respond(
                """[{"id":"a1","name":"","prompt":"Check the darts tickets\nand report","schedule":{"kind":"interval","minutes":30},
                   "schedule_display":"every 30m","state":"scheduled","enabled":true,"next_run_at":"2026-10-03T04:30:00+02:00",
                   "last_run_at":null,"repeat":{"times":null,"completed":3},"origin":null}]""",
                HttpStatusCode.OK, json,
            )
        }))

        val job = assertIs<ApiResult.Success<List<CronJob>>>(api.jobs(url)).value.single()

        assertEquals("profile=all", query)
        assertEquals("Check the darts tickets", job.displayName)
        assertEquals("every 30m", job.scheduleDisplay)
        assertFalse(job.paused)
        assertEquals(1790994600.0, job.nextRunEpochSeconds)
    }

    @Test
    fun runsAcceptRawSqliteFlags() = runTest {
        val api = CronApi(createHttpClient(MockEngine { request ->
            assertEquals("/api/cron/jobs/a1/runs", request.url.encodedPath)
            respond(
                """{"runs":[{"id":"cron_a1_20261003_040000","source":"cron","title":"Darts","started_at":1.0,
                   "message_count":4,"archived":false,"pinned":0,"hidden":0,"is_active":true}],"limit":50}""",
                HttpStatusCode.OK, json,
            )
        }))

        val run = assertIs<ApiResult.Success<List<SessionSummary>>>(api.runs(url, "a1")).value.single()

        assertFalse(run.pinned)
        assertTrue(run.isActive)
        assertEquals(4, run.messageCount)
    }

    @Test
    fun pauseReturnsTheUpdatedJob() = runTest {
        var method: HttpMethod? = null
        val api = CronApi(createHttpClient(MockEngine { request ->
            method = request.method
            assertEquals("/api/cron/jobs/a1/pause", request.url.encodedPath)
            respond("""{"id":"a1","name":"Darts","state":"paused","enabled":false}""", HttpStatusCode.OK, json)
        }))

        val job = assertIs<ApiResult.Success<CronJob>>(api.pause(url, "a1")).value

        assertEquals(HttpMethod.Post, method)
        assertTrue(job.paused)
    }
}

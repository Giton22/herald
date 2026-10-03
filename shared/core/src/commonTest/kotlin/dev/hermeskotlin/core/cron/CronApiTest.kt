package dev.hermeskotlin.core.cron

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.SessionSummary
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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

    @Test
    fun createPostsTheDraftAndReturnsTheJob() = runTest {
        var body: String? = null
        val api = CronApi(createHttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/cron/jobs", request.url.encodedPath)
            body = request.body.toByteArray().decodeToString()
            respond(
                """{"id":"b2","name":"Briefing","prompt":"Brief me","schedule":{"kind":"cron","expr":"0 9 * * *","display":"0 9 * * *"},
                   "schedule_display":"0 9 * * *","state":"scheduled","enabled":true,"deliver":"telegram"}""",
                HttpStatusCode.OK, json,
            )
        }))

        val job = assertIs<ApiResult.Success<CronJob>>(
            api.create(url, CronJobDraft(name = "Briefing", prompt = "Brief me", schedule = "0 9 * * *", deliver = "telegram")),
        ).value

        assertEquals("""{"name":"Briefing","prompt":"Brief me","schedule":"0 9 * * *","deliver":"telegram"}""", body)
        assertEquals("b2", job.id)
        assertEquals("0 9 * * *", job.editableSchedule)
        assertEquals("telegram", job.deliver)
    }

    @Test
    fun updateSendsOnlyTheChangesAndSurfacesABadSchedule() = runTest {
        var body: String? = null
        val api = CronApi(createHttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/cron/jobs/a1", request.url.encodedPath)
            body = request.body.toByteArray().decodeToString()
            respond("""{"detail":"Invalid schedule 'sometimes'"}""", HttpStatusCode.BadRequest, json)
        }))

        val result = api.update(url, "a1", mapOf("schedule" to "sometimes"))

        assertEquals("""{"updates":{"schedule":"sometimes"}}""", body)
        assertEquals("Invalid schedule 'sometimes'", assertIs<ApiResult.Failed>(result).message)
    }

    @Test
    fun deleteAndDeliveryTargets() = runTest {
        val api = CronApi(createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/cron/jobs/a1" -> {
                    assertEquals(HttpMethod.Delete, request.method)
                    respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
                else -> respond(
                    """{"targets":[{"id":"local","name":"Local (save only)","home_target_set":true,"home_env_var":null},
                       {"id":"telegram","name":"Telegram","home_target_set":false,"home_env_var":"TELEGRAM_HOME_CHANNEL"}]}""",
                    HttpStatusCode.OK, json,
                )
            }
        }))

        assertIs<ApiResult.Success<Unit>>(api.delete(url, "a1"))
        val targets = assertIs<ApiResult.Success<List<DeliveryTarget>>>(api.deliveryTargets(url)).value
        assertEquals(listOf("local", "telegram"), targets.map { it.id })
        assertFalse(targets[1].homeTargetSet)
    }

    @Test
    fun editableScheduleFromEachShape() {
        fun job(schedule: String, display: String) =
            HermesJson.decodeFromString<CronJob>("""{"id":"x","schedule":$schedule,"schedule_display":"$display"}""")

        assertEquals("every 90m", job("""{"kind":"interval","minutes":90}""", "every 90m").editableSchedule)
        assertEquals("", job("""{"kind":"once","run_at":"2026-10-04T09:00:00+03:00"}""", "once at 2026-10-04 09:00").editableSchedule)
        // A legacy job that stored the schedule as a plain string still loads.
        assertEquals("0 7 * * *", job("\"0 7 * * *\"", "0 7 * * *").editableSchedule)
    }
}

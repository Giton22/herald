package dev.hermeskotlin.core.insights

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InsightsApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    // Trimmed from a real gateway's answer.
    private val body = """{"daily":[
        {"day":"2026-09-26","input_tokens":731736,"output_tokens":47529,"cache_read_tokens":4666112,"reasoning_tokens":44232,
         "estimated_cost":0.0,"actual_cost":0,"sessions":1,"api_calls":52},
        {"day":"2026-09-28","input_tokens":1000,"output_tokens":500,"estimated_cost":0.25,"actual_cost":0,"sessions":2,"api_calls":3}],
      "by_model":[{"model":"grok-4.7","input_tokens":13779295,"output_tokens":889577,"estimated_cost":0.028,"sessions":53,"api_calls":1148,
         "aux_tasks":[{"task":"vision","input_tokens":2794,"output_tokens":1177,"estimated_cost":0,"api_calls":1}]}],
      "by_task":[{"task":"background_review","input_tokens":1086652,"output_tokens":8064,"estimated_cost":0,"api_calls":66,"models":["gpt-6-sol"]}],
      "totals":{"total_input":16017108,"total_output":1226523,"total_cache_read":83437088,"total_reasoning":1003380,
         "total_estimated_cost":0.58,"total_actual_cost":0,"total_sessions":90,"total_api_calls":1457},
      "period_days":7,
      "skills":{"summary":{"total_skill_loads":269,"distinct_skills_used":19},
         "top_skills":[{"skill":"hermes-agent","view_count":65,"manage_count":0,"total_count":65,"percentage":24.1,"last_used_at":1790868772.7}]},
      "tools":[{"tool":"terminal","count":815,"percentage":19.3}]}"""

    @Test
    fun parsesARealReport() = runTest {
        var query: String? = null
        val api = InsightsApi(createHttpClient(MockEngine { request ->
            query = request.url.encodedQuery
            respond(body, HttpStatusCode.OK, json)
        }))

        val report = assertIs<ApiResult.Success<UsageReport>>(api.usage(url, 7, "ruby")).value

        assertEquals("days=7&profile=ruby", query)
        assertEquals(90, report.totals.sessions)
        assertEquals(83, report.totals.cachePercent)
        assertEquals(0.58, report.totals.cost)
        assertEquals("grok-4.7", report.byModel.single().model)
        assertEquals(65, report.topSkills.single().count)
        assertEquals(815, report.tools.single().count)
    }

    @Test
    fun dailySeriesFillsTheGapsUpToToday() = runTest {
        val api = InsightsApi(createHttpClient(MockEngine { respond(body, HttpStatusCode.OK, json) }))
        val report = assertIs<ApiResult.Success<UsageReport>>(api.usage(url, 7, null)).value

        val series = report.dailySeries(lastEpochDay = epochDayOf("2026-09-30")!!)

        assertEquals(
            listOf("2026-09-24", "2026-09-25", "2026-09-26", "2026-09-27", "2026-09-28", "2026-09-29", "2026-09-30"),
            series.map { it.day },
        )
        assertEquals(listOf(0L, 0L, 779265L, 0L, 1500L, 0L, 0L), series.map { it.tokens })
    }

    @Test
    fun civilDatesRoundTrip() {
        assertEquals(0, epochDayOf("1970-01-01"))
        for (iso in listOf("2024-02-29", "2026-10-03", "2000-03-01", "1999-12-31")) assertEquals(iso, isoDateOf(epochDayOf(iso)!!))
        assertEquals(null, epochDayOf("yesterday"))
    }
}

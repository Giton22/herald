package dev.hermeskotlin.core.config

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun schemaReadsFieldsWithTheirChoices() = runTest {
        var query: String? = null
        val api = ConfigApi(createHttpClient(MockEngine { request ->
            query = request.url.encodedQuery
            respond(
                """{"fields":{
                     "approvals.mode":{"type":"select","description":"Dangerous command approval mode","category":"security","options":["manual","smart","off"]},
                     "timezone":{"type":"select","description":"IANA timezone","category":"general","options":["America/New_York"],"searchable":true,"clearable":true},
                     "agent.max_turns":{"type":"number","description":null,"category":"agent"}},
                   "category_order":["general","agent"]}""",
                HttpStatusCode.OK, json,
            )
        }))

        val schema = assertIs<ApiResult.Success<ConfigSchema>>(api.schema(url, "rin")).value

        assertEquals("profile=rin", query)
        assertEquals(listOf("manual", "smart", "off"), schema.fields.getValue("approvals.mode").choices)
        assertTrue(schema.fields.getValue("timezone").searchable)
        assertEquals("", schema.fields.getValue("agent.max_turns").description)
        assertEquals(listOf("general", "agent"), schema.categoryOrder)
    }

    @Test
    fun saveSendsOnlyThePatchUnderConfig() = runTest {
        var sent: String? = null
        var method: HttpMethod? = null
        val api = ConfigApi(createHttpClient(MockEngine { request ->
            method = request.method
            sent = "${request.url.encodedPath}?${request.url.encodedQuery} ${request.body.toByteArray().decodeToString()}"
            respond("""{"ok":true}""", HttpStatusCode.OK, json)
        }))

        assertIs<ApiResult.Success<Unit>>(api.save(url, "rin", configPatch("approvals.mode", JsonPrimitive("smart"))))

        assertEquals(HttpMethod.Put, method)
        assertEquals("""/api/config?profile=rin {"config":{"approvals":{"mode":"smart"}}}""", sent)
    }

    @Test
    fun rawRoundTripsTheFile() = runTest {
        val seen = mutableListOf<String>()
        val api = ConfigApi(createHttpClient(MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPath} ${request.body.toByteArray().decodeToString()}"
            if (request.method == HttpMethod.Get) {
                respond("""{"yaml":"model: kimi-k3\n","path":"/home/me/.hermes/config.yaml"}""", HttpStatusCode.OK, json)
            } else {
                respond("""{"ok":true}""", HttpStatusCode.OK, json)
            }
        }))

        val raw = assertIs<ApiResult.Success<RawConfig>>(api.raw(url, null)).value
        assertEquals("model: kimi-k3\n", raw.yaml)
        assertIs<ApiResult.Success<Unit>>(api.saveRaw(url, null, "model: glm-5.3\n"))

        assertEquals("""PUT /api/config/raw {"yaml_text":"model: glm-5.3\n"}""", seen.last())
    }

    @Test
    fun aRefusedYamlSaysWhy() = runTest {
        val api = ConfigApi(createHttpClient(MockEngine {
            respond("""{"detail":"YAML must be a mapping"}""", HttpStatusCode.BadRequest, json)
        }))

        val failed = assertIs<ApiResult.Failed>(api.saveRaw(url, null, "- just a list"))

        assertEquals("YAML must be a mapping", failed.message)
    }
}

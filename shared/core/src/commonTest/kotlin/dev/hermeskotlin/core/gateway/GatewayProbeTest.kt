package dev.hermeskotlin.core.gateway

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
import kotlin.test.assertTrue

class GatewayProbeTest {

    private val url = GatewayUrl.parse("100.64.0.1:9119")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun probeWith(engine: MockEngine) = GatewayProbe(createHttpClient(engine))

    @Test
    fun parsesGatedDashboardStatus() = runTest {
        val engine = MockEngine { request ->
            assertEquals("http://100.64.0.1:9119/api/status", request.url.toString())
            respond(
                """{"version":"0.42.0","gateway_running":true,"auth_required":true,
                   "auth_providers":["basic"],"auth_flows":["cookie","native_pkce"],
                   "profiles":["default","work"],"unknown_field":{"x":1}}""",
                HttpStatusCode.OK, json,
            )
        }
        val result = assertIs<ProbeResult.Reachable>(probeWith(engine).probe(url))
        assertEquals("0.42.0", result.status.version)
        assertTrue(result.status.supportsPasswordLogin)
        assertTrue(result.status.supportsNativeSignIn)
        assertTrue(result.canSignIn)
        assertEquals(listOf("default", "work"), result.status.profiles)
    }

    @Test
    fun gatedWithoutPasswordProviderCannotSignIn() = runTest {
        val engine = MockEngine {
            respond("""{"version":"0.42.0","auth_required":true,"auth_providers":["nous"]}""", HttpStatusCode.OK, json)
        }
        val result = assertIs<ProbeResult.Reachable>(probeWith(engine).probe(url))
        assertEquals(false, result.canSignIn)
    }

    @Test
    fun nonHermesResponse() = runTest {
        val engine = MockEngine { respond("<html>nginx</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        assertIs<ProbeResult.NotHermes>(probeWith(engine).probe(url))
    }

    @Test
    fun httpErrorIsNotHermes() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val result = assertIs<ProbeResult.NotHermes>(probeWith(engine).probe(url))
        assertEquals(404, result.httpStatus)
    }

    @Test
    fun connectionFailureIsUnreachable() = runTest {
        val engine = MockEngine { throw IllegalStateException("Connection refused") }
        val result = assertIs<ProbeResult.Unreachable>(probeWith(engine).probe(url))
        assertEquals("Connection refused", result.reason)
    }
}

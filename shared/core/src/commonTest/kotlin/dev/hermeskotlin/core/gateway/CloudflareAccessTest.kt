package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.network.ACCESS_BLOCKED
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflareAccessTest {

    private val url = GatewayUrl.parse("https://hermes.example.com")
    private val token = AccessToken("id.access", "secret")
    private val store = InMemoryKeyValueStore()
    private val access = AccessTokens(store)
    private val seen = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val accessLogin = headersOf(
        HttpHeaders.Location,
        "https://team.cloudflareaccess.com/cdn-cgi/access/login/hermes.example.com?kid=abc&redirect_url=%2Fapi%2Fstatus",
    )

    private fun client(handler: MockRequestHandler) =
        createHttpClient(MockEngine { request -> seen += request; handler(request) }, access = access)

    @Test
    fun tokenGoesOnlyToItsOwnHost() = runTest {
        access.set("Hermes.Example.com", token)
        val http = client { respond("{}", HttpStatusCode.OK, json) }

        http.get("https://hermes.example.com/api/status")
        http.get("https://elsewhere.example.com/api/status")

        assertEquals("id.access", seen[0].headers[CF_ACCESS_CLIENT_ID])
        assertEquals("secret", seen[0].headers[CF_ACCESS_CLIENT_SECRET])
        assertNull(seen[1].headers[CF_ACCESS_CLIENT_ID])
        assertNull(seen[1].headers[CF_ACCESS_CLIENT_SECRET])
    }

    @Test
    fun tokenIsNeverSentInPlainText() = runTest {
        access.set("hermes.example.com", token)
        val http = client { respond("{}", HttpStatusCode.OK, json) }

        http.get("http://hermes.example.com:9119/api/status")

        assertNull(seen.single().headers[CF_ACCESS_CLIENT_ID])
        assertNull(seen.single().headers[CF_ACCESS_CLIENT_SECRET])
    }

    @Test
    fun accessCookieIsNotKeptSoTheHostDoesNotLookSignedIn() = runTest {
        access.set(url.host, token)
        val cookies = PersistentCookiesStorage(store)
        val setCookie = headersOf(HttpHeaders.SetCookie to listOf("CF_Authorization=jwt; Path=/; Secure; HttpOnly"), HttpHeaders.ContentType to listOf("application/json"))
        val http = createHttpClient(MockEngine { respond("{}", HttpStatusCode.OK, setCookie) }, cookies, access)

        http.get(url.resolve("api/status"))

        assertFalse(cookies.hasCookies(Url(url.value)))
    }

    @Test
    fun retainOnlyForgetsTokensOfOtherHosts() = runTest {
        access.set("hermes.example.com", token)
        access.set("old.example.com", token)

        access.retainOnly(listOf("HERMES.example.com."))

        assertEquals(token, access.get("hermes.example.com"))
        assertNull(access.get("old.example.com"))
    }

    @Test
    fun relativeAccessLocationIsRecognised() {
        assertTrue(isAccessLoginRedirect(302, "/cdn-cgi/access/login/hermes.example.com"))
        assertFalse(isAccessLoginRedirect(200, "/cdn-cgi/access/login/hermes.example.com"))
        assertFalse(isAccessLoginRedirect(302, null))
    }

    @Test
    fun tokensSurviveARestartAndCanBeForgotten() = runTest {
        access.set("hermes.example.com", token)
        assertEquals(token, AccessTokens(store).get("hermes.example.com"))

        access.set("hermes.example.com", null)
        assertNull(AccessTokens(store).get("hermes.example.com"))
    }

    @Test
    fun accessLoginRedirectIsNamedInTheProbe() = runTest {
        val probe = GatewayProbe(client { respond("", HttpStatusCode.Found, accessLogin) })

        val result = assertIs<ProbeResult.AccessBlocked>(probe.probe(url))

        assertEquals(302, result.httpStatus)
        assertFalse(result.tokenSent)
        val failed = assertIs<StageResult.Failed>(result.toStageResult())
        assertTrue("Cloudflare Access" in failed.problem)
        assertTrue("service token" in failed.fix)
    }

    @Test
    fun cloudflareRefusingASentTokenSaysTheTokenWasTurnedDown() = runTest {
        access.set(url.host, token)
        val forbidden = headersOf(HttpHeaders.Server to listOf("cloudflare"), HttpHeaders.ContentType to listOf("text/html"))
        val probe = GatewayProbe(client { respond("<html>Forbidden</html>", HttpStatusCode.Forbidden, forbidden) })

        val result = assertIs<ProbeResult.AccessBlocked>(probe.probe(url))

        assertTrue(result.tokenSent)
        val failed = assertIs<StageResult.Failed>(result.toStageResult())
        assertTrue("turned down" in failed.problem)
    }

    @Test
    fun anOrdinaryRedirectIsStillNotHermes() = runTest {
        val probe = GatewayProbe(client { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/login")) })

        assertIs<ProbeResult.NotHermes>(probe.probe(url))
    }

    @Test
    fun apiCallsStoppedByAccessSaySo() = runTest {
        val http = client { respond("", HttpStatusCode.Found, accessLogin) }

        val result = assertIs<ApiResult.Failed>(apiCall { http.get(url.resolve("api/auth/me")) })

        assertEquals(ACCESS_BLOCKED, result.message)
    }

    @Test
    fun apiCallsRefusedByCloudflareSaySo() = runTest {
        val forbidden = headersOf(HttpHeaders.Server to listOf("cloudflare"), HttpHeaders.ContentType to listOf("text/html"))
        val http = client { respond("<html>Forbidden</html>", HttpStatusCode.Forbidden, forbidden) }

        val result = assertIs<ApiResult.Failed>(apiCall { http.get(url.resolve("api/auth/me")) })

        assertEquals(ACCESS_BLOCKED, result.message)
    }
}

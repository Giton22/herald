package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.rpc.HandshakeRejectedException
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionCheckTest {

    private val url = GatewayUrl.parse("100.64.0.1:9119")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun check(
        signedIn: Boolean = true,
        statusUp: Boolean = true,
        openSocket: suspend (GatewayUrl, String) -> RpcTransport = { _, _ -> FakeTransport().apply { push(FakeTransport.READY) } },
    ): ConnectionCheck {
        val http = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/status" ->
                        if (statusUp) respond("""{"version":"0.42.0","auth_required":true,"auth_providers":["basic"]}""", HttpStatusCode.OK, json)
                        else throw RuntimeException("Connection refused")
                    "/api/auth/me" ->
                        if (signedIn) respond("""{"display_name":"Zed"}""", HttpStatusCode.OK, json)
                        else respondError(HttpStatusCode.Unauthorized)
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    else -> respondError(HttpStatusCode.Unauthorized)
                }
            },
        )
        return ConnectionCheck(GatewayProbe(http), AuthApi(http, PersistentCookiesStorage(InMemoryKeyValueStore())), openSocket, readyTimeoutMs = 1_000)
    }

    @Test
    fun aWorkingGatewayPassesEveryStage() = runTest {
        val report = check().run(url)

        CheckStage.entries.forEach { assertIs<StageResult.Passed>(report.results[it], "$it") }
        assertNull(report.failedStage)
        assertTrue((report.results[CheckStage.SignIn] as StageResult.Passed).detail.contains("Zed"))
    }

    @Test
    fun anUnreachableServerStopsAtTheFirstStageWithATailscaleFix() = runTest {
        val report = check(statusUp = false).run(url)

        assertEquals(CheckStage.Server, report.failedStage)
        assertTrue((report.results[CheckStage.Server] as StageResult.Failed).fix.contains("Tailscale"))
        assertIs<StageResult.Skipped>(report.results[CheckStage.SignIn])
        assertIs<StageResult.Skipped>(report.results[CheckStage.Live])
    }

    @Test
    fun aReachableServerDoesNotImplyASignIn() = runTest {
        val report = check(signedIn = false).run(url)

        assertIs<StageResult.Passed>(report.results[CheckStage.Server])
        assertEquals(CheckStage.SignIn, report.failedStage)
        assertTrue((report.results[CheckStage.SignIn] as StageResult.Failed).fix.contains("Sign in"))
        assertIs<StageResult.Skipped>(report.results[CheckStage.Live])
    }

    @Test
    fun aRefusedChatUpgradeFailsOnlyTheLiveStage() = runTest {
        val report = check(openSocket = { _, _ -> throw HandshakeRejectedException(403, RuntimeException("403")) }).run(url)

        assertIs<StageResult.Passed>(report.results[CheckStage.Server])
        assertIs<StageResult.Passed>(report.results[CheckStage.SignIn])
        assertEquals(CheckStage.Live, report.failedStage)
        assertTrue((report.results[CheckStage.Live] as StageResult.Failed).problem.contains("Host/Origin"))
    }

    @Test
    fun aSocketThatNeverSaysReadyFailsTheLiveStage() = runTest {
        val report = check(openSocket = { _, _ -> FakeTransport() }).run(url)

        assertEquals(CheckStage.Live, report.failedStage)
        assertTrue((report.results[CheckStage.Live] as StageResult.Failed).problem.contains("never said it was ready"))
    }

    @Test
    fun theReachFixFollowsTheKindOfAddress() {
        assertTrue(reachFix(GatewayUrl.parse("hermes.tail1234.ts.net")).contains("Tailscale"))
        assertTrue(reachFix(GatewayUrl.parse("192.168.1.20:9119")).contains("same Wi-Fi"))
        assertTrue(reachFix(GatewayUrl.parse("https://hermes.example.com")).contains("certificate"))
    }

    @Test
    fun aLoopbackAddressIsCalledOutAsThisPhone() {
        listOf("localhost:9119", "127.0.0.1:9119", "http://[::1]:9119").forEach {
            val fix = reachFix(GatewayUrl.parse(it))
            assertTrue(fix.contains("points at this phone"), "$it: $fix")
        }
    }

    @Test
    fun httpsOnAPrivateNameGetsTheCertificateFix() {
        assertTrue(reachFix(GatewayUrl.parse("https://hermes.lan")).contains("certificate"))
        assertTrue(reachFix(GatewayUrl.parse("https://hermes.tail1234.ts.net")).contains("Tailscale"))
    }

    @Test
    fun aFailedProbeSkipsTheLaterStages() {
        val results = ProbeResult.Unreachable(url, "Connection refused").serverOnlyResults()

        assertIs<StageResult.Failed>(results[CheckStage.Server])
        assertIs<StageResult.Skipped>(results[CheckStage.SignIn])
        assertIs<StageResult.Skipped>(results[CheckStage.Live])
    }

    @Test
    fun aReachableProbeLeavesTheLaterStagesToSignIn() = runTest {
        val probe = GatewayProbe(
            createHttpClient(MockEngine { respond("""{"version":"0.42.0","auth_required":true,"auth_providers":["basic"]}""", HttpStatusCode.OK, json) }),
        )
        val results = probe.probe(url).serverOnlyResults()

        assertEquals(setOf(CheckStage.Server), results.keys)
        assertIs<StageResult.Passed>(results[CheckStage.Server])
    }
}

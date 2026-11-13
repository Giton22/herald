package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeSession
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.wearlink.HistoryRequest
import dev.hermeskotlin.wearlink.WatchScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchTranscriptReaderTest {
    private val owner = WatchScope(FakeGateway.URL.value, "work")
    private class Setup(val reader: WatchTranscriptReader, val gateway: FakeGateway, val profiles: ProfileStore, val gateways: GatewayRepository)
    private suspend fun TestScope.setup(): Setup {
        val store = InMemoryKeyValueStore()
        val gateway = FakeGateway(backgroundScope)
        gateway.start()
        val tokens = NativeTokens(store).apply { set(FakeGateway.URL, NativeSession("access", "refresh")) }
        val auth = AuthApi(gateway.client, PersistentCookiesStorage(store), tokens)
        val gateways = GatewayRepository(store).apply { save(SavedGateway(FakeGateway.URL.value)) }
        val profiles = ProfileStore(store).apply { set(FakeGateway.URL, "work") }
        return Setup(WatchTranscriptReader(gateways, auth, profiles, gateway.connection, SessionsApi(gateway.client), BotsApi(gateway.connection)), gateway, profiles, gateways)
    }
    private fun request(bot: String? = null, offset: Int = 0) =
        HistoryRequest("request", owner.copy(profile = bot ?: owner.profile), owner, "saved", bot, offset)

    @Test
    fun readsStoredRowsNewestTwentyAndPagesByRawRowsIncludingHiddenTools() = runTest {
        val s = setup()
        var received: HttpRequestData? = null
        s.gateway.http = { req ->
            received = req
            json("""{"session_id":"saved","messages":[{"id":1,"role":"user","content":"question"},{"id":2,"role":"assistant","content":"old","display_content":"reply\r\n```code```"},{"id":3,"role":"tool","content":"private tool"},{"id":4,"role":"assistant","content":"hidden","display_kind":"hidden"}]}""")
        }
        val page = s.reader.read(request(offset = 20))
        val sent = assertNotNull(received)
        assertEquals("/api/sessions/saved/messages", sent.url.encodedPath)
        assertEquals("work", sent.url.parameters["profile"])
        assertEquals("20", sent.url.parameters["limit"])
        assertEquals("20", sent.url.parameters["offset"])
        assertEquals("latest", sent.url.parameters["order"])
        assertEquals(listOf("question", "reply\r\n```code```"), page.messages.map { it.text })
        assertEquals(null, page.nextOffset)
        assertEquals("saved", page.chatId)
    }

    @Test
    fun botBrowsingReconnectsAndUsesCanonicalStoredTipWithoutCreatingOrResuming() = runTest {
        val s = setup()
        s.gateway.connection.stop()
        s.gateway.answer = { when (it.method) {
            "profiles.list" -> """{"profiles":[{"name":"helper","canonical_session":{"id":"root","resolved_id":"tip"}}]}"""
            "client.capabilities" -> "{}"
            else -> error("Unexpected ${it.method}")
        } }
        var received: HttpRequestData? = null
        s.gateway.http = { req -> received = req; json("""{"session_id":"tip","messages":[]}""") }
        val page = s.reader.read(request("helper"))
        val sent = assertNotNull(received)
        assertEquals("/api/sessions/tip/messages", sent.url.encodedPath)
        assertEquals("helper", sent.url.parameters["profile"])
        assertEquals("tip", page.chatId)
        assertTrue(page.messages.isEmpty())
        assertTrue(s.gateway.sent("session.create").isEmpty())
        assertTrue(s.gateway.sent("session.resume").isEmpty())
    }

    @Test
    fun missingBotHistoryAndOldGatewayNeverFallBackToOrdinaryChat() = runTest {
        val s = setup()
        s.gateway.answer = { """{"profiles":[{"name":"helper"}]}""" }
        assertNotNull(s.reader.read(request("helper")).error)
        s.gateway.answer = { "error:-32601" }
        assertNotNull(s.reader.read(request("helper")).error)
        assertTrue(s.gateway.sent("session.create").isEmpty())
    }

    @Test
    fun wrongGatewayOrBotProfileIsRefusedBeforeAnyHistoryRead() = runTest {
        val s = setup()
        assertNotNull(s.reader.read(request().copy(scope = owner.copy(gateway = "https://other.example"))).error)
        assertNotNull(s.reader.read(request("helper").copy(scope = owner)).error)
        assertTrue(s.gateway.sent("profiles.list").isEmpty())
    }

    @Test
    fun profileOrGatewaySwitchWhileHistoryIsLoadingDropsPrivateRows() = runTest {
        for (gatewaySwitch in listOf(false, true)) {
            val s = setup()
            val arrived = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            s.gateway.http = { arrived.complete(Unit); release.await(); json("""{"session_id":"saved","messages":[{"role":"user","content":"old owner"}]}""") }
            val pending = async { s.reader.read(request()) }
            withTimeout(5_000) { arrived.await() }
            if (gatewaySwitch) s.gateways.save(SavedGateway("https://other.example")) else s.profiles.set(FakeGateway.URL, "other")
            release.complete(Unit)
            val page = pending.await()
            assertNotNull(page.error)
            assertTrue(page.messages.isEmpty())
        }
    }
    @Test
    fun fullRawPageOffersOlderRowsEvenWhenEveryRowIsFiltered() = runTest {
        val s = setup()
        val rows = (1..20).joinToString(",") { """{"id":$it,"role":"tool","content":"tool output"}""" }
        s.gateway.http = { json("""{"session_id":"saved","messages":[$rows]}""") }
        val page = s.reader.read(request())
        assertTrue(page.messages.isEmpty())
        assertEquals(20, page.nextOffset)
    }

    @Test
    fun missingHistoryRouteIsAnErrorAndEmptyTranscriptIsNot() = runTest {
        val s = setup()
        s.gateway.http = { respond("Not Found", HttpStatusCode.NotFound) }
        assertNotNull(s.reader.read(request()).error)
        s.gateway.http = { json("""{"session_id":"saved","messages":[]}""") }
        val page = s.reader.read(request())
        assertEquals(null, page.error)
        assertTrue(page.messages.isEmpty())
        assertEquals(null, page.nextOffset)
    }

    @Test
    fun savedLaunchProfileDoesNotFollowPhonesSelectedProfile() = runTest {
        val s = setup()
        var received: HttpRequestData? = null
        s.gateway.http = { req -> received = req; json("""{"session_id":"saved","messages":[]}""") }
        val page = s.reader.read(request().copy(scope = owner.copy(profile = null)))
        assertEquals(null, page.error)
        assertEquals(null, assertNotNull(received).url.parameters["profile"])
    }

    @Test
    fun timeoutEndsTheReadWithAnErrorAndNoRows() = runTest {
        val s = setup()
        s.gateway.http = { delay(30_000); json("""{"session_id":"saved","messages":[]}""") }
        val page = s.reader.read(request())
        assertNotNull(page.error)
        assertTrue(page.messages.isEmpty())
    }

}

package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeSession
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.CallTarget
import dev.hermeskotlin.wearlink.DefaultCallTarget
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.WatchAsk
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class WatchBotsTest {
    private val roster = """{"profiles":[{"name":"assistant","display_name":"Assistant","canonical_session":{"id":"root","resolved_id":"tip"}},{"name":"hidden","ui_meta":{"hermes-bots":{"hidden":true}}}]}"""
    private class Outbox : WatchOutbox {
        val messages = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun send(node: String, path: String, data: ByteArray): Boolean {
            messages += path to WearJson.decode<JsonObject>(data)!!
            return true
        }
        override suspend fun stream(node: String, path: String, data: ByteArray) = true
    }
    private var transcribedProfile: String? = null
    private val voiceChecks = mutableListOf<String?>()
    private data class Setup(val relay: WatchRelay, val gateway: FakeGateway, val outbox: Outbox, val settings: SettingsStore)

    private suspend fun TestScope.setup(profiles: String = roster, store: KeyValueStore = InMemoryKeyValueStore(), liveForAll: Boolean = false): Setup {
        val gateway = FakeGateway(backgroundScope).apply {
            ready = { FakeGateway.QUIET_READY }
            http = { request ->
                if (request.url.encodedPath == "/api/audio/voice-live/status") {
                    val profile = request.url.parameters["profile"]
                    voiceChecks += profile
                    json(if (profile == "assistant" || liveForAll) """{"mode":"gpt-live","available":true}""" else """{"mode":"chained","available":true}""")
                } else if (request.url.encodedPath == "/api/audio/transcribe") {
                    transcribedProfile = request.url.parameters["profile"]
                    json("""{"transcript":"hello"}""")
                } else if (request.url.encodedPath == "/api/sessions") json("""{"sessions":[{"id":"ordinary","title":"Plans"}]}""")
                else json("""{"session_id":"tip","messages":[]}""")
            }
            answer = { call -> when (call.method) {
                "profiles.list" -> profiles
                "session.resume" -> """{"session_id":"runtime","running":false}"""
                else -> "{}"
            } }
        }
        gateway.start()
        val tokens = NativeTokens(store).apply { set(FakeGateway.URL, NativeSession("test-access", "test-refresh")) }
        val auth = AuthApi(gateway.client, PersistentCookiesStorage(store), tokens)
        val gateways = GatewayRepository(store).apply { save(SavedGateway(FakeGateway.URL.value)) }
        val sessions = SessionsApi(gateway.client)
        val outbox = Outbox()
        val bots = BotsApi(gateway.connection)
        val settings = SettingsStore(store, backgroundScope)
        val relay = WatchRelay(gateways, auth, ProfileStore(store), gateway.connection, sessions,
            AudioApi(gateway.client), ChatHost(gateway.connection, sessions, backgroundScope), backgroundScope, outbox, bots, BotChats(bots), settings)
        return Setup(relay, gateway, outbox, settings)
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }
    private fun ask(profile: String) = WearJson.decode<WatchAsk>(
        """{"id":"ask","text":"hello","chatId":"stale","speak":false,"botProfile":"$profile"}""".encodeToByteArray(),
    )!!

    private suspend fun Setup.target(id: String): DefaultCallTarget {
        relay.defaultCall("watch", id)
        until { outbox.messages.any { it.first == WearPaths.DEFAULT_CALL && it.second["requestId"]?.jsonPrimitive?.content == id } }
        return WearJson.decode<DefaultCallTarget>(WearJson.encode(outbox.messages.last { it.first == WearPaths.DEFAULT_CALL }.second))!!
    }

    @Test
    fun defaultCallsUseCurrentSettingsAndNewSessionNeverReusesTheLastCall() = runTest {
        val s = setup(liveForAll = true)
        s.settings.update { it.copy(callTargets = mapOf(FakeGateway.URL.value to CallTarget(sessionId = "ordinary", label = "Plans"))) }
        assertEquals("ordinary", s.target("existing").chatId)
        s.settings.update { it.copy(callTargets = emptyMap()) }
        assertEquals(null, s.target("fresh1").chatId)
        assertEquals(null, s.target("fresh2").chatId)
    }

    @Test
    fun defaultCallsUseTheSelectedProfileOnlyForFreshSessions() = runTest {
        val store = InMemoryKeyValueStore()
        ProfileStore(store).set(FakeGateway.URL, "work")
        val s = setup(store = store, liveForAll = true)
        val fresh = s.target("fresh")
        assertEquals(WatchScope(FakeGateway.URL.value, "work"), fresh.scope)
        assertEquals("work", voiceChecks.last())
        assertEquals("work", assertIs<CallChat.Ready>(s.relay.chatForCall(fresh.chatId,
            defaultRequestId = fresh.requestId, node = "watch")).profile)
        val messageProfiles = mutableListOf<String?>()
        val http = s.gateway.http
        s.gateway.http = { request ->
            if (request.url.encodedPath.endsWith("/messages")) messageProfiles += request.url.parameters["profile"]
            http(request)
        }
        s.settings.update { it.copy(callTargets = mapOf(FakeGateway.URL.value to CallTarget(sessionId = "ordinary"))) }
        val existing = s.target("existing")
        assertEquals(null, voiceChecks.last())
        ProfileStore(store).set(FakeGateway.URL, "other")
        assertEquals(null, assertIs<CallChat.Ready>(s.relay.chatForCall(existing.chatId,
            defaultRequestId = existing.requestId, node = "watch")).profile)
        assertTrue(messageProfiles.isNotEmpty())
        assertTrue(messageProfiles.all { it == null })
        assertEquals(null, assertIs<CallChat.Ready>(s.relay.chatForCall(existing.chatId, scope = existing.scope)).profile)
    }

    @Test
    fun scopedAsksAndRecordingsRetainLaunchProfileAndRejectAnotherGateway() = runTest {
        val store = InMemoryKeyValueStore()
        ProfileStore(store).set(FakeGateway.URL, "work")
        val s = setup(store = store)
        val owner = WatchScope(FakeGateway.URL.value)
        s.relay.ask("watch", WatchAsk("text", "hello", "ordinary", speak = false, scope = owner))
        until { s.gateway.sent("session.resume").isNotEmpty() }
        assertEquals(null, s.gateway.sent("session.resume").last().param("profile"))
        s.relay.ask("watch", WatchAsk("record", chatId = "ordinary", speak = false, scope = owner), ByteArray(32))
        until { s.outbox.messages.any { it.second["askId"]?.jsonPrimitive?.content == "record" && it.second["heard"] != null } }
        assertEquals(null, transcribedProfile)
        val resumes = s.gateway.sent("session.resume").size
        var transcriptions = 0
        val http = s.gateway.http
        s.gateway.http = { request ->
            if (request.url.encodedPath == "/api/audio/transcribe") transcriptions++
            http(request)
        }
        val wrong = WatchScope("https://other.example")
        assertIs<CallChat.Failed>(s.relay.chatForCall("ordinary", scope = wrong))
        s.relay.ask("watch", WatchAsk("wrong", "hello", "ordinary", scope = wrong), ByteArray(32))
        until { s.outbox.messages.any { it.second["askId"]?.jsonPrimitive?.content == "wrong" && it.second["phase"]?.jsonPrimitive?.content == "Failed" } }
        assertEquals(resumes, s.gateway.sent("session.resume").size)
        assertEquals(0, transcriptions)
        assertIs<CallChat.Failed>(s.relay.chatForCall("stale", "assistant", scope = owner))
    }

    @Test
    fun defaultBotResolvesCanonicalConversationAndRemovedBotDoesNotFallBack() = runTest {
        val s = setup()
        s.settings.update { it.copy(callTargets = mapOf(FakeGateway.URL.value to CallTarget(profile = "assistant"))) }
        val target = s.target("bot")
        assertEquals("assistant", target.botProfile)
        assertEquals("Assistant", target.title)
        assertEquals("tip", target.chatId)
        s.settings.update { it.copy(callTargets = mapOf(FakeGateway.URL.value to CallTarget(profile = "removed"))) }
        assertTrue(s.target("missing").error != null)
        assertTrue(s.gateway.sent("session.create").isEmpty())
    }

    @Test
    fun settingsChangeOrAnotherWatchCannotUseAnOldDefaultResolution() = runTest {
        val s = setup(liveForAll = true)
        val target = s.target("first")
        s.settings.update { it.copy(callTargets = mapOf(FakeGateway.URL.value to CallTarget(sessionId = "ordinary"))) }
        assertIs<CallChat.Failed>(s.relay.chatForCall(target.chatId, defaultRequestId = "first", node = "watch"))
        s.target("second")
        assertIs<CallChat.Failed>(s.relay.chatForCall("ordinary", defaultRequestId = "second", node = "other"))
        assertTrue(s.gateway.sent("session.resume").isEmpty())
    }

    @Test
    fun aLateResolutionCannotReplaceTheNewerRequest() = runTest {
        val s = setup(liveForAll = true)
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = s.gateway.http
        var calls = 0
        s.gateway.http = { request ->
            if (request.url.encodedPath == "/api/audio/voice-live/status" && ++calls == 1) {
                arrived.complete(Unit)
                release.await()
            }
            http(request)
        }
        s.relay.defaultCall("watch", "slow")
        arrived.await()
        assertEquals(null, s.target("newer").error)
        release.complete(Unit)
        until { s.outbox.messages.any { it.second["requestId"]?.jsonPrimitive?.content == "slow" } }
        assertTrue(s.relay.defaultCallCurrent("watch", "newer"))
        assertTrue(!s.relay.defaultCallCurrent("watch", "slow"))
    }

    @Test
    fun changingSettingsDuringHistoryLoadCannotReturnTheOldDefaultCall() = runTest {
        val s = setup(liveForAll = true)
        s.settings.update { it.copy(callTargets = mapOf(FakeGateway.URL.value to CallTarget(sessionId = "ordinary"))) }
        s.target("call")
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = s.gateway.http
        s.gateway.http = { request ->
            if (request.url.encodedPath.endsWith("/messages")) { arrived.complete(Unit); release.await() }
            http(request)
        }
        val call = async { s.relay.chatForCall("ordinary", defaultRequestId = "call", node = "watch") }
        arrived.await()
        s.settings.update { it.copy(callTargets = emptyMap()) }
        release.complete(Unit)
        assertIs<CallChat.Failed>(call.await())
    }

    @Test
    fun aDefaultWithNoLiveVoiceResolvesForAnAskWithoutCreatingOrResumingAChat() = runTest {
        val s = setup()
        s.settings.update { it.copy(callTargets = mapOf(FakeGateway.URL.value to CallTarget(sessionId = "ordinary", label = "Plans"))) }
        val target = s.target("not-live")
        assertEquals(null, target.error)
        assertEquals(false, target.live)
        assertEquals("ordinary", target.chatId)
        assertTrue(s.gateway.sent("session.create").isEmpty())
        assertTrue(s.gateway.sent("session.resume").isEmpty())
    }

    @Test
    fun anUnavailableSavedDefaultDoesNotFallBackToANewSession() = runTest {
        val s = setup(liveForAll = true)
        s.settings.update { it.copy(callTargets = mapOf(FakeGateway.URL.value to CallTarget(sessionId = "removed"))) }
        s.gateway.http = { json("{}", io.ktor.http.HttpStatusCode.NotFound) }
        assertTrue(s.target("removed").error != null)
        assertTrue(s.gateway.sent("session.create").isEmpty())
        assertTrue(s.gateway.sent("session.resume").isEmpty())
    }

    @Test
    fun thePickerListsVisibleBotsSeparatelyFromRegularSessions() = runTest {
        val s = setup()
        s.relay.chats("watch")
        until { s.outbox.messages.any { it.first == WearPaths.CHATS } }
        val list = s.outbox.messages.first { it.first == WearPaths.CHATS }.second
        assertEquals("ordinary", (list["chats"] as JsonArray).first().let { (it as JsonObject)["id"]!!.jsonPrimitive.content })
        val bots = list["bots"] as? JsonArray
        assertEquals(1, bots?.size)
        assertEquals("assistant", (bots!!.first() as JsonObject)["profile"]!!.jsonPrimitive.content)
    }

    @Test
    fun aBotAskResolvesTheCurrentConversationAndUsesItsProfile() = runTest {
        val s = setup()
        s.relay.ask("watch", ask("assistant"))
        until { s.gateway.sent("session.resume").isNotEmpty() }
        val resumed = s.gateway.sent("session.resume").first()
        assertEquals("assistant", resumed.param("profile"))
        assertEquals("tip", resumed.param("session_id"))
        assertTrue(s.gateway.sent("session.create").isEmpty())
    }

    @Test
    fun aRemovedBotFailsInsteadOfSendingToRegularHermes() = runTest {
        val s = setup("""{"profiles":[]}""")
        s.relay.ask("watch", ask("removed"))
        until { s.gateway.sent("session.resume").isNotEmpty() || s.outbox.messages.any { it.second["phase"]?.jsonPrimitive?.content == "Failed" } }
        assertTrue(s.gateway.sent("session.resume").isEmpty())
        assertTrue(s.gateway.sent("session.create").isEmpty())
        assertTrue(s.gateway.sent("session.send").isEmpty())
    }

    @Test
    fun recordedSpeechUsesTheBotsTranscriberAndConversation() = runTest {
        val s = setup()
        s.relay.ask("watch", ask("assistant").copy(audioType = "audio/wav"), ByteArray(32))
        until { s.gateway.sent("session.resume").isNotEmpty() }
        assertEquals("assistant", transcribedProfile)
        assertEquals("assistant", s.gateway.sent("session.resume").first().param("profile"))
        assertEquals("tip", s.gateway.sent("session.resume").first().param("session_id"))
    }

    @Test
    fun callsUseTheBotsCurrentConversationAndRegularCallsStillUseTheSelectedSession() = runTest {
        val s = setup()
        assertEquals("assistant", assertIs<CallChat.Ready>(s.relay.chatForCall("stale", "assistant")).profile)
        assertEquals("tip", s.gateway.sent("session.resume").first().param("session_id"))
        s.relay.chatForCall("ordinary")
        val regular = s.gateway.sent("session.resume").last()
        assertEquals("ordinary", regular.param("session_id"))
        assertEquals(null, regular.param("profile"))
    }

    @Test
    fun delayedListsKeepTheirRequestedProfileAndIdentity() = runTest {
        val memory = InMemoryKeyValueStore()
        val profiles = ProfileStore(memory)
        profiles.set(FakeGateway.URL, "work")
        val s = setup(store = memory)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = s.gateway.http
        s.gateway.http = { request ->
            if (request.url.encodedPath == "/api/sessions" && request.url.parameters["profile"] == "work") {
                entered.complete(Unit)
                release.await()
            }
            original(request)
        }
        s.relay.chats("watch", "work-list")
        entered.await()
        profiles.set(FakeGateway.URL, "other")
        s.relay.chats("watch", "other-list")
        until { s.outbox.messages.any { it.second["requestId"]?.jsonPrimitive?.content == "other-list" } }
        release.complete(Unit)
        until { s.outbox.messages.count { it.first == WearPaths.CHATS } == 2 }
        val lists = s.outbox.messages.map { WearJson.decode<dev.hermeskotlin.wearlink.ChatList>(WearJson.encode(it.second))!! }
        assertEquals(listOf("other-list", "work-list"), lists.map { it.requestId })
        assertEquals(listOf("other", "work"), lists.map { it.scope?.profile })
        assertTrue(lists.all { it.bots.single().profile == "assistant" })
    }

    @Test
    fun statusEchoesTheCheckAndSeparatesBrowsingFromTheConversationOwner() = runTest {
        val memory = InMemoryKeyValueStore()
        ProfileStore(memory).set(FakeGateway.URL, "work")
        val s = setup(store = memory)
        s.relay.status("watch", "assistant", "check", WatchScope(FakeGateway.URL.value, "assistant"))
        until { s.outbox.messages.any { it.first == WearPaths.STATUS } }
        val status = WearJson.decode<dev.hermeskotlin.wearlink.PhoneStatus>(WearJson.encode(s.outbox.messages.first().second))!!
        assertEquals("check", status.requestId)
        assertEquals(WatchScope(FakeGateway.URL.value, "work"), status.activeScope)
        assertEquals(WatchScope(FakeGateway.URL.value, "assistant"), status.scope)
        assertTrue(status.profileSync)
    }

    @Test
    fun liveVoiceAvailabilityBelongsToTheSelectedProfileRatherThanTheLastBotChecked() = runTest {
        val s = setup()
        s.relay.status("watch", "assistant")
        until { s.outbox.messages.any { it.first == WearPaths.STATUS && it.second["liveVoice"]?.jsonPrimitive?.content == "true" } }
        assertEquals("assistant", s.outbox.messages.last().second["botProfile"]?.jsonPrimitive?.content)
        s.outbox.messages.clear()
        s.relay.status("watch")
        until { s.outbox.messages.any { it.first == WearPaths.STATUS && it.second["liveVoice"]?.jsonPrimitive?.content == "false" } }
        assertEquals(listOf("assistant", null), voiceChecks)
    }

    @Test
    fun aResolvedVoiceCheckIsDeliveredEvenWhenAnotherProfileReplacesTheCache() = runTest {
        var profileRead: () -> Unit = {}
        val memory = InMemoryKeyValueStore()
        val store = object : KeyValueStore by memory {
            override suspend fun get(key: String): String? {
                if (key.startsWith("profile.v1.")) profileRead()
                return memory.get(key)
            }
        }
        val s = setup(store = store)
        var reads = 0
        profileRead = {
            // An older bot check completes while the latest regular-chat status is being read.
            if (++reads == 3) s.relay.liveVoiceIs(FakeGateway.URL, true, "assistant")
        }
        s.relay.status("watch")
        until { reads >= 3 }
        val resolved = s.outbox.messages.last().second
        assertEquals("false", resolved["liveVoice"]?.jsonPrimitive?.content)
        assertEquals(null, resolved["botProfile"]?.jsonPrimitive?.content)
    }

    @Test
    fun incomingCallsDoNotUseTheLastBotsVoiceAvailability() = runTest {
        val s = setup()
        s.relay.liveVoiceIs(FakeGateway.URL, true, "assistant")
        assertEquals(false, s.relay.talksLive)
        s.relay.checkLiveVoice()
        until { voiceChecks.isNotEmpty() }
        assertEquals(listOf<String?>(null), voiceChecks)
        assertEquals(false, s.relay.talksLive)
    }

    @Test
    fun theRingersCheckDoesNotPushOutTheWatchChatsProfile() = runTest {
        val s = setup()
        s.relay.status("watch", "assistant")
        until { voiceChecks.size == 1 }
        s.relay.checkLiveVoice()
        until { voiceChecks.size == 2 }
        s.outbox.messages.clear()
        s.relay.status("watch", "assistant")
        until { s.outbox.messages.any { it.first == WearPaths.STATUS } }
        // Both answers are still fresh: neither profile is asked again.
        s.relay.checkLiveVoice()
        delay(100)
        assertEquals(listOf("assistant", null), voiceChecks)
        assertEquals("true", s.outbox.messages.last { it.first == WearPaths.STATUS }.second["liveVoice"]?.jsonPrimitive?.content)
    }

    @Test
    fun incomingCallsStillUseTheLaunchProfileAfterCheckingABot() = runTest {
        val s = setup(liveForAll = true)
        s.relay.liveVoiceIs(FakeGateway.URL, false, "assistant")
        s.relay.checkLiveVoice()
        until { s.relay.talksLive }
        assertEquals(listOf<String?>(null), voiceChecks)
    }
}

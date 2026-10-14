package dev.hermeskotlin.core.inbox

import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxApiTest {

    private object Keys : PushKeys {
        override val deviceId = "device-1"
        override fun registration(name: String): PushRegistration = error("not registering in tests")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway) = false
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String) = emptySet<String>()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = null
    }

    /** The `/herald-push cron …` calls sent, as the command's argument. */
    private val sent = mutableListOf<String>()

    /** A gateway whose plugin prints what [plugin] says for each `cron` argument. */
    private suspend fun TestScope.api(plugin: (String) -> String): InboxApi {
        val fake = FakeGateway(backgroundScope)
        fake.answer = { call ->
            if (call.method == "command.dispatch") {
                val arg = call.param("arg").orEmpty().removePrefix("cron ")
                sent += arg
                val output = JsonPrimitive(plugin(arg)).toString()
                """{"type":"plugin","output":$output}"""
            } else {
                "{}"
            }
        }
        return InboxApi(PushApi(fake.start()), Keys)
    }

    private fun body(arg: String): JsonObject = Json.parseToJsonElement(arg.substringAfter(' ')).jsonObject

    @Test
    fun allOnAgainstAPluginWithoutADefaultIsTooOldNotNothingFollowed() = runTest {
        // Plugin 0.4.0: `default` is ignored, and the reply has none.
        val api = api { """{"ok":true,"jobs":{},"failures":false}""" }
        val e = assertFailsWith<InboxPluginTooOld> { api.setFollowing(Following().all(Follow.Quiet)) }
        assertEquals(InboxApi.ALL_JOBS_PLUGIN_VERSION, e.needs)
        assertEquals(Follow.Quiet.wire, body(sent.single())["default"]?.jsonPrimitive?.content)
    }

    @Test
    fun aJobSwitchedOffAgainstPlugin040IsTooOld() = runTest {
        val api = api { """{"ok":false,"error":"mode must be notify or quiet"}""" }
        val e = assertFailsWith<InboxPluginTooOld> { api.setFollowing(Following(default = Follow.Quiet).with("noisy", Follow.Off)) }
        assertEquals(InboxApi.ALL_JOBS_PLUGIN_VERSION, e.needs)
    }

    @Test
    fun aSaveTheGatewayEchoesComesBackAsItSays() = runTest {
        val api = api { """{"ok":true,"jobs":{"noisy":"off"},"failures":true,"default":"quiet"}""" }
        val saved = api.setFollowing(Following(default = Follow.Quiet, failures = true).with("noisy", Follow.Off))
        assertEquals(Follow.Off, saved.of("noisy"))
        assertEquals(Follow.Quiet, saved.of("other"))
        assertTrue(saved.failures)
    }

    @Test
    fun theUsageLineIsAPluginTooOldForTheInbox() = runTest {
        // 0.4.0 has `follow` and `following` only, and its usage line names them (and so contains "cron ").
        val usage = "usage: /herald-push info | register <json> | cron follow <json> | cron following <json>"
        val api = api { """{"ok":false,"error":"$usage"}""" }
        val list = assertFailsWith<InboxPluginTooOld> { api.runs() }
        assertEquals(InboxApi.MIN_PLUGIN_VERSION, list.needs)
        assertEquals(InboxApi.TOO_OLD, list.message)
        assertFailsWith<InboxPluginTooOld> { api.read(InboxRun(job = "j", run = "2026-10-12_08-00-00")) }
    }

    @Test
    fun anAnswerThatDoesntReadIsAnErrorNotAnOldPlugin() = runTest {
        val api = api { "Traceback (most recent call last)" }
        // An RpcException, which InboxPluginTooOld isn't: the Inbox shows it as an error, not the update notice.
        assertFailsWith<RpcException> { api.runs() }
    }

    @Test
    fun aRunTheGatewayNoLongerHasIsNullAndAFailedReadIsAnError() = runTest {
        var reply = """{"ok":false,"error":"no such run"}"""
        val api = api { reply }
        assertNull(api.read(InboxRun(job = "j", run = "2026-10-12_08-00-00")))
        reply = """{"ok":false,"error":"bad bot, job or run"}"""
        assertFailsWith<RpcException> { api.read(InboxRun(job = "j", run = "2026-10-12_08-00-00")) }
    }

    @Test
    fun aPageBackAsksBeforeTheGivenTimeAndAsManyAsThePluginLists() = runTest {
        val api = api { """{"ok":true,"runs":[]}""" }
        api.runs(before = 1_760_000_000)
        val asked = body(sent.single())
        assertEquals(1_760_000_000, asked["before"]?.jsonPrimitive?.long)
        assertEquals(InboxApi.PAGE.toLong(), asked["limit"]?.jsonPrimitive?.long)
        api.runs()
        assertNull(body(sent.last())["before"])
    }
}

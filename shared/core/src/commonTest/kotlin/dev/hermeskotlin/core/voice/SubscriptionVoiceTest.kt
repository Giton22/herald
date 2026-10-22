package dev.hermeskotlin.core.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionVoiceTest {

    @Test
    fun readsWhatTheVoiceSaysAndHandsOver() {
        assertEquals(SubscriptionEvent.Started, parseSubscriptionEvent("""{"type":"session.started"}"""))
        assertEquals(
            SubscriptionEvent.Heard(LiveFragment.Speaker.User, "turn the"),
            parseSubscriptionEvent("""{"type":"input_transcript.added","item":{"text":"turn the"}}"""),
        )
        assertEquals(
            SubscriptionEvent.Heard(LiveFragment.Speaker.Assistant, "Sure"),
            parseSubscriptionEvent("""{"type":"output_transcript.added","item":{"text":"Sure"}}"""),
        )
        assertEquals(
            SubscriptionEvent.TurnDone(LiveFragment.Speaker.User, "Turn the lights off"),
            parseSubscriptionEvent("""{"type":"turn.done","turn":{"role":"user","id":"t1","transcript":"Turn the lights off"}}"""),
        )
        assertEquals(
            SubscriptionEvent.Delegation("d1", "Turn the kitchen lights off"),
            parseSubscriptionEvent(
                """{"type":"delegation.created","item":{"id":"d1","content":[{"type":"input_text","text":"Turn the kitchen "},{"type":"input_text","text":"lights off"}]}}""",
            ),
        )
        assertEquals(SubscriptionEvent.Failure("rate limited"), parseSubscriptionEvent("""{"type":"error","error":{"message":"rate limited"}}"""))
    }

    @Test
    fun leavesOutWhatHeraldHasNoUseFor() {
        assertNull(parseSubscriptionEvent("""{"type":"turn.delta","delta":"x"}"""))
        assertNull(parseSubscriptionEvent("""{"type":"input_transcript.added","item":{"text":""}}"""))
        assertNull(parseSubscriptionEvent("""{"type":"turn.done","turn":{"role":"system"}}"""))
        assertNull(parseSubscriptionEvent("""{"type":"delegation.created","item":{"content":[]}}"""))
        assertNull(parseSubscriptionEvent("not json"))
    }

    @Test
    fun aResultGoesBackToTheRequestItAnswers() {
        val event = Json.parseToJsonElement(SubscriptionCommands.delegationResult("d1", "x".repeat(20_000))).jsonObject
        assertEquals("delegation.context.append", event["type"]!!.jsonPrimitive.content)
        assertEquals("d1", event["delegation_item_id"]!!.jsonPrimitive.content)
        val content = event["content"]!!.jsonArray.single().jsonObject
        assertEquals("input_text", content["type"]!!.jsonPrimitive.content)
        assertEquals(SubscriptionCommands.RESULT_CHARS, content["text"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun readsHowLongTheVoiceSpoke() {
        assertEquals(SubscriptionEvent.Usage(4200), parseSubscriptionEvent("""{"type":"session.usage.updated","usage":{"audio_duration_ms":4200}}"""))
        assertNull(parseSubscriptionEvent("""{"type":"session.usage.updated","usage":{}}"""))
    }

    @Test
    fun thePersonaPicksTheProfileThePluginIsAskedFor() {
        val chat = SubscriptionVoiceOptions()
        assertEquals("work", chat.profileFor("work"))
        // The default profile is the gateway's own bot.
        assertNull(chat.profileFor("default"))
        assertNull(chat.profileFor(null))
        assertNull(SubscriptionVoiceOptions(persona = SubscriptionVoiceOptions.PERSONA_MAIN).profileFor("work"))
        assertEquals("research", SubscriptionVoiceOptions(persona = "research").profileFor("work"))
    }

    @Test
    fun readsTheSignInThePluginRuns() {
        val json = Json { ignoreUnknownKeys = true }
        val status = json.decodeFromString<SubscriptionVoiceStatus>(
            """{"ok":true,"configured":false,"codexOauth":"missing","login":{"status":"pending","url":"https://auth.openai.com/codex/device","code":"K7QM-42XPA","message":null}}""",
        )
        assertFalse(status.signedIn)
        assertTrue(status.login!!.pending)
        assertEquals("K7QM-42XPA", status.login!!.code)
    }

    @Test
    fun everyVoiceIsNamedOnce() {
        assertEquals(9, SUBSCRIPTION_VOICES.map { it.id }.toSet().size)
        assertEquals("Maple", SUBSCRIPTION_VOICES.single { it.id == "maple" }.label)
    }

    @Test
    fun onlyASignedInPluginCanTalk() {
        assertTrue(SubscriptionVoiceStatus(ok = true, codexOauth = "valid").signedIn)
        assertTrue(SubscriptionVoiceStatus(ok = true, codexOauth = "expired").signedIn)
        assertFalse(SubscriptionVoiceStatus(ok = true, codexOauth = "missing").signedIn)
        assertFalse(SubscriptionVoiceStatus(ok = false, codexOauth = "valid").signedIn)
    }
}

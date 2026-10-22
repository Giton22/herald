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
    fun onlyASignedInPluginCanTalk() {
        assertTrue(SubscriptionVoiceStatus(ok = true, codexOauth = "valid").signedIn)
        assertTrue(SubscriptionVoiceStatus(ok = true, codexOauth = "expired").signedIn)
        assertFalse(SubscriptionVoiceStatus(ok = true, codexOauth = "missing").signedIn)
        assertFalse(SubscriptionVoiceStatus(ok = false, codexOauth = "valid").signedIn)
    }
}

package dev.hermeskotlin.core.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VoiceLiveTest {

    private fun user(text: String, at: Long) = LiveFragment(LiveFragment.Speaker.User, text, at, at + 100)
    private fun voice(text: String, at: Long) = LiveFragment(LiveFragment.Speaker.Assistant, text, at, at + 100)

    @Test
    fun readsTheEventsHeraldActsOn() {
        val transcript = assertIs<LiveEvent.Transcript>(parseLiveEvent("""{"type":"session.input_transcript.delta","delta":"hi ","start_ms":10,"end_ms":250}"""))
        assertEquals(user("hi ", 10).copy(endMs = 250), transcript.fragment)
        assertEquals(LiveFragment.Speaker.Assistant, (parseLiveEvent("""{"type":"session.output_transcript.delta","delta":"yo"}""") as LiveEvent.Transcript).fragment.speaker)
        assertEquals(LiveEvent.Delegation("d1"), parseLiveEvent("""{"type":"session.delegation.created","delegation":{"id":"d1","type":"client","target":"backend"}}"""))
        assertEquals(LiveEvent.Failure("bad"), parseLiveEvent("""{"type":"error","error":{"message":"bad"}}"""))
        assertEquals(LiveEvent.Closed("idle_timeout", 42.5), parseLiveEvent("""{"type":"session.closed","reason":"idle_timeout","usage":{"seconds":42.5}}"""))
        assertEquals(LiveEvent.Started, parseLiveEvent("""{"type":"session.started","session":{"id":"s"}}"""))
    }

    @Test
    fun ignoresNoiseAndUnknownEvents() {
        // A late append after our own close is expected, not a failure to show.
        assertNull(parseLiveEvent("""{"type":"error","error":{"code":"context_injection_incomplete","message":"late"}}"""))
        assertNull(parseLiveEvent("""{"type":"session.something_new"}"""))
        assertNull(parseLiveEvent("not json"))
        assertNull(parseLiveEvent("""{"type":"session.delegation.created"}"""))
    }

    @Test
    fun commandsCarryTheDelegationTheyAnswer() {
        val say = Json.parseToJsonElement(LiveCommands.commentary("say_1", "d1", "Done.")).jsonObject
        assertEquals("session.commentary.append", say["type"]!!.jsonPrimitive.content)
        assertEquals("d1", say["delegation_id"]!!.jsonPrimitive.content)
        assertEquals("Done.", say["content"]!!.jsonPrimitive.content)
        val steer = Json.parseToJsonElement(LiveCommands.instructions("i_1", "Be brief.")).jsonObject
        assertEquals(JsonNull, steer["delegation_id"])
        assertEquals("session.input_audio.mute", Json.parseToJsonElement(LiveCommands.mute("m", true)).jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun aLongReplyIsCutAtSentencesWithinTheAppendLimit() {
        assertEquals(listOf("Short one."), commentaryChunks("  Short\n one. "))
        assertEquals(emptyList(), commentaryChunks("   "))
        val chunks = commentaryChunks("First sentence here. Second one is here. Third.", limit = 25)
        assertEquals(listOf("First sentence here.", "Second one is here.", "Third."), chunks)
        // A sentence longer than the limit is split where it must.
        val x = "x".repeat(10)
        assertEquals(listOf(x, x, x, ".", "End."), commentaryChunks("$x$x$x. End.", limit = 10))
    }

    @Test
    fun theOpeningHistoryKeepsTheNewestTurnsThatFit() {
        val turns = listOf(
            LiveFragment.Speaker.User to "old question",
            LiveFragment.Speaker.Assistant to "old answer",
            LiveFragment.Speaker.User to "  ",
            LiveFragment.Speaker.User to "new question",
        )
        val history = liveHistory(turns, maxMessages = 2)
        assertEquals(listOf("assistant", "user"), history.map { it.role })
        assertEquals(listOf("output_text", "input_text"), history.map { it.content.single().type })
        assertEquals("new question", history.last().content.single().text)
        assertEquals(1, liveHistory(turns, maxChars = 15).size)
    }

    @Test
    fun aDelegationAsksWhatWasSaidLastWithTheExchangeAroundIt() {
        val ask = delegationPrompt(
            listOf(user("Book the ", 0), user("table for Friday.", 200), voice("Sure, for Friday?", 400), user("Thursday, not Friday.", 900)),
        )
        assertEquals("Thursday, not Friday.", ask.prompt)
        assertEquals("User: Book the table for Friday.\nVoice assistant: Sure, for Friday?\nUser: Thursday, not Friday.", ask.context)
        // Nothing the person said: the tail of the exchange stands in.
        assertEquals("Voice assistant: Hello", delegationPrompt(listOf(voice("Hello", 0))).prompt)
    }
}

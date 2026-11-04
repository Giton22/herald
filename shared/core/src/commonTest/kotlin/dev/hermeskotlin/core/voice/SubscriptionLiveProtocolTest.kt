package dev.hermeskotlin.core.voice

import kotlin.test.*
import kotlinx.serialization.json.*

class SubscriptionLiveProtocolTest {
    @Test fun transcriptAndDelegation() {
        val p = SubscriptionLiveProtocol()
        assertEquals(LiveEvent.Started, p.receive("""{"type":"session.updated"}""").single())
        val first = p.receive("""{"type":"input_transcript.added","item":{"text":"Hello "},"start_ms":42}""").single() as LiveEvent.Transcript
        assertEquals(LiveFragment(LiveFragment.Speaker.User, "Hello ", 0, 0), first.fragment)
        val final = p.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"Hello world"}}""")
        assertEquals(listOf(LiveEvent.UserFinal("Hello world")), final)
        assertEquals("Hi", (p.receive("""{"type":"output_transcript.added","item":{"text":"Hi"}}""").single() as LiveEvent.Transcript).fragment.text)
        assertTrue(p.receive("""{"type":"turn.done","turn":{"role":"assistant","transcript":"Hi"}}""").isEmpty())
        assertEquals(LiveEvent.Delegation("id"), p.receive("""{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"id","content":[{"type":"input_text","text":"not a user event"}]}}""").single())
        assertTrue(p.receive("""{"type":"delegation.created","item":{"type":"delegation","target":"server","id":"id","content":[]}}""").isEmpty())
        assertEquals(LiveEvent.Failure("Subscription voice provider error."), p.receive("""{"type":"error","error":{"message":"secret"}}""").single())
    }
    @Test fun correctedAndMissingFinalNeverMasqueradeAsStreamedText() {
        val p = SubscriptionLiveProtocol()
        p.receive("""{"type":"input_transcript.added","item":{"text":"yes"}}""")
        assertEquals(listOf(LiveEvent.UserFinal("no")), p.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"no"}}"""))
        p.receive("""{"type":"input_transcript.added","item":{"text":"yes"}}""")
        assertEquals(listOf(LiveEvent.UserFinal(null)), p.receive("""{"type":"turn.done","turn":{"role":"user"}}"""))
    }
    @Test fun appendBytesAndChannels() {
        val p = SubscriptionLiveProtocol()
        val text = "é".repeat(249) + "😀" + "xyz".repeat(200)
        val parts = p.send(LiveCommands.commentary("x", "delegation", text)).map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it["type"]!!.jsonPrimitive.content == "delegation.context.append" && it["channel"]!!.jsonPrimitive.content == "speakable" && it["delegation_item_id"]!!.jsonPrimitive.content == "delegation" })
        val chunks = parts.map { it["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content }
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.encodeToByteArray().size <= 500 })
        val quiet = Json.parseToJsonElement(p.send(LiveCommands.thinking("x", null, "working")).single()).jsonObject
        assertEquals("session.context.append", quiet["type"]!!.jsonPrimitive.content)
        assertEquals("commentary", quiet["channel"]!!.jsonPrimitive.content)
        assertTrue("delegation_item_id" !in quiet)
        assertTrue(p.send(LiveCommands.mute("x", true)).isEmpty())
        assertEquals(listOf(LiveCommands.close()), p.send(LiveCommands.close()))
    }
}

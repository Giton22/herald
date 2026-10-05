package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptEventsTest {

    // The row the test gateway stored in test1's Bot Chat after it messaged @hermes (trimmed).
    private val deliveryRow = """[IMPORTANT: Background process proc_acee4d1163fd completed normally (exit code 0).
Command: /opt/hermes/.venv/bin/python3 /opt/hermes/tools/bot_mode_dm.py --run-delivery --author '{"id":"bot:test1","name":"test1","is_bot":true}' query-file /opt/data/profiles/test1/cache/scratch/hermes-dm-568/dm-oxhqd2fp.txt --profile-home /opt/data /opt/hermes/.venv/bin/hermes -p default chat --in '~' -c 'Bot Chat' --create-if-missing -Q
Output:
{"reply": "Status from my end: idle.\n\nDelivery confirmed.", "status": "settled", "delivery_id": "2cc997"}
]"""

    @Test
    fun aDeliveryReceiptSaysWhoAnswered() {
        val event = TranscriptRows.classify(deliveryRow, displayKind = null)?.single()
        assertEquals(TranscriptEvent.Delivery("hermes", DeliveryOutcome.Replied("Status from my end: idle.\n\nDelivery confirmed.")), event)
    }

    @Test
    fun deliveryOutcomesInEachShape() {
        fun outcome(output: String, failed: Boolean = false) = TranscriptRows.deliveryOutcome(output, failed)
        assertIs<DeliveryOutcome.Waiting>(outcome("""{"status":"queued","delivery_id":"d","detail":"Delivery remains pending"}"""))
        assertEquals(
            DeliveryOutcome.Failed("target_busy", "Delivery failed: @x's Bot Chat is open on another surface"),
            outcome("""{"error":"Delivery failed: @x's Bot Chat is open on another surface","reason":"target_busy"}""", failed = true),
        )
        assertEquals("their chat is open elsewhere", (outcome("""{"error":"e","reason":"target_busy"}""") as DeliveryOutcome.Failed).label)
        assertEquals(DeliveryOutcome.Replied("hi"), outcome("Reply from Mac Mini:\nhi"))
        assertEquals("delivery_timeout", (outcome("Delivery to Mac Mini failed [reason: delivery_timeout]: slow") as DeliveryOutcome.Failed).reason)
        assertIs<DeliveryOutcome.Waiting>(outcome("No reply from Mac Mini within 300s. The message may still be processed; do not resend blindly."))
        // A plain CLI turn prints the reply, maybe with its session id.
        assertEquals(DeliveryOutcome.Replied("Done."), outcome("session_id: 2026_x\nDone."))
        assertEquals(DeliveryOutcome.Replied("A fact."), outcome("A fact.\n\n↻ Resumed session 20261005_005623 (Bot Chat)"))
        assertEquals(
            DeliveryOutcome.Replied("No further action needed."),
            outcome("No further action needed. Session 20261005023510 found but has no messages. Starting fresh."),
        )
        assertEquals(DeliveryOutcome.NoReply, outcome(""))
        assertEquals(DeliveryOutcome.NoReply, outcome("(no reply)"))
    }

    @Test
    fun aDeliveryIsNamedByItsAckWhenTheCommandDoesntSay() {
        val row = "[IMPORTANT: Background process proc_9 completed normally (exit code 0).\nCommand: python runner.py\nOutput:\nsure]"
        val event = TranscriptRows.classify(row, "process_complete", deliveries = mapOf("proc_9" to "scribe"))?.single()
        assertEquals(TranscriptEvent.Delivery("scribe", DeliveryOutcome.Replied("sure")), event)
        assertEquals("proc_9" to "scribe", TranscriptRows.deliveryAck("""{"status":"queued","delivery_id":"d","to":"@scribe","process_id":"proc_9"}"""))
    }

    @Test
    fun otherProcessesFoldToOneLine() {
        val row = "[IMPORTANT: Background process proc_1 exited (exit code 2).\nCommand: npm test\nOutput:\n2 failing]"
        assertEquals(TranscriptEvent.Process("Background process finished: npm test", "2 failing", failed = true), TranscriptRows.classify(row, null)?.single())
        val batch = "[IMPORTANT: 2 background processes completed. Review below.]\n\n" +
            "[IMPORTANT: Background process a completed normally (exit code 0).\nCommand: ls\nOutput:\nx]\n\n" +
            "[IMPORTANT: Background process b completed normally (exit code 0).\nCommand: pwd\nOutput:\ny]"
        assertEquals(2, TranscriptRows.classify(batch, "process_complete")?.size)
    }

    @Test
    fun agentMessagesRoutinesAndLabels() {
        assertEquals(
            TranscriptEvent.FromBot("hermes", "hermes", "Quick status check"),
            TranscriptRows.classify("Message from 🤖 hermes (@hermes): Quick status check", null)?.single(),
        )
        assertEquals(
            TranscriptEvent.Routine("Morning brief", "3 new emails"),
            TranscriptRows.classify("[Cronjob \"Morning brief\" output — scheduled job, not the user. Review it, act on anything that needs action, and summarize for the chat.]\n\n3 new emails", null)?.single(),
        )
        assertEquals(TranscriptEvent.Routine("Tickets", "found 2"), TranscriptRows.classify("[Cron delivery: Tickets]\nfound 2", null)?.single())
        assertEquals(TranscriptEvent.Label("Model changed"), TranscriptRows.classify("switched", "model_switch")?.single())
        assertNull(TranscriptRows.classify("What's the weather?", null))
        // A prompt that merely talks about these stays the user's.
        assertNull(TranscriptRows.classify("Why did the [IMPORTANT: Background process thing show up?", null))
    }

    @Test
    fun noiseAndSilence() {
        assertTrue(TranscriptRows.isNoise("[System: compacted]", null))
        assertTrue(TranscriptRows.isNoise("[Background process p1 heartbeat #3 — still running", null))
        assertTrue(TranscriptRows.isNoise("wake", "internal_notification"))
        assertFalse(TranscriptRows.isNoise("hello", null))
        listOf("[SILENT]", "NO_REPLY", " no reply ", "*NO_REPLY*", ".NO_REPLY", "静默").forEach { assertTrue(TranscriptRows.isSilence(it), it) }
        listOf("", "I'll stay [SILENT] for now", "SILENTLY").forEach { assertFalse(TranscriptRows.isSilence(it), it) }
    }

    @Test
    fun historyFoldsRepliesToOtherBotsAndHidesSilence() {
        fun row(id: Long, role: String, text: String, toolCalls: String? = null, toolCallId: String? = null, kind: String? = null) = SessionMessage(
            id = id, role = role, content = JsonPrimitive(text), toolCalls = toolCalls?.let(HermesJson::parseToJsonElement),
            toolCallId = toolCallId, displayKind = kind,
        )
        val messages = historyToMessages(
            listOf(
                row(1, "user", "Message from 🤖 hermes (@hermes): how is it going?"),
                row(2, "assistant", "All idle here."),
                row(3, "user", "Message from 🤖 scribe (@scribe): ping"),
                row(4, "assistant", "NO_REPLY"),
                row(5, "user", "Ask scribe for the draft"),
                row(6, "assistant", "", toolCalls = """[{"id":"c1","function":{"name":"message_agent","arguments":"{\"target\":\"@scribe\",\"message\":\"draft?\"}"}}]"""),
                row(7, "tool", """{"status":"queued","delivery_id":"d","to":"@scribe","process_id":"proc_5"}""", toolCallId = "c1"),
                row(8, "user", "Message from 🤖 scribe (@scribe): here it is"),
                row(9, "assistant", "Scribe sent the draft."),
                row(10, "assistant", "Your request was not processed.", kind = "failed_turn"),
            ),
        )
        val replies = messages.filterIsInstance<ChatMessage.Assistant>()
        assertEquals("hermes", replies[0].repliedTo)
        // The silent answer to scribe's ping shows nothing at all.
        assertFalse(replies.any { it.text == "NO_REPLY" })
        // Scribe answering this bot's own question isn't folded away: it's what the person asked for.
        assertNull(replies.last { it.text == "Scribe sent the draft." }.repliedTo)
        assertIs<TranscriptEvent.FailedTurn>((messages.last() as ChatMessage.Event).event)
        assertEquals(3, messages.count { (it as? ChatMessage.Event)?.event is TranscriptEvent.FromBot })
    }
}

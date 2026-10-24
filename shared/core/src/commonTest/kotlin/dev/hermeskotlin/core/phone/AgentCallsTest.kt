package dev.hermeskotlin.core.phone

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentCallsTest {

    @Test
    fun aCallEndsOnceWithWhateverCameFirst() = runTest {
        val call = AgentCall(CallReason("The deploy failed"))
        call.end(CallOutcome.Answered)
        call.end(CallOutcome.Declined)
        assertEquals(CallOutcome.Answered, call.outcome())
        assertEquals("answered", CallOutcome.Answered.status)
        assertEquals("no_answer", CallOutcome.NoAnswer.status)
    }

    @Test
    fun theLiveVoiceIsToldWhyAndGivenTheDetailsOnlyToAnswerFrom() {
        val opener = CallReason("  The deploy failed. ", "Build 42 broke on the tests.").liveOpener()
        assertTrue(opener.contains("tell them why, briefly: The deploy failed."))
        assertTrue(opener.contains("not to read out: Build 42 broke on the tests."))
        assertFalse(CallReason("Wake up", " ").liveOpener().contains("Background"))
        assertEquals("Hi, it's Hermes. Wake up", CallReason(" Wake up ").spokenOpener())
    }

    @Test
    fun aReasonPickedUpOnTheWatchOpensThatChatsCallOnce() {
        var now = 0L
        val openers = CallOpeners { now }
        val reason = CallReason("Check-in closes")
        openers.put("chat-1", reason)
        assertNull(openers.take("chat-2"))
        assertNull(openers.take(null))
        assertEquals(reason, openers.take("chat-1"))
        assertNull(openers.take("chat-1"))
        // A call that never started: the reason doesn't open a later one.
        openers.put("chat-1", reason)
        now += CallOpeners.KEEP_MS
        assertNull(openers.take("chat-1"))
    }
}

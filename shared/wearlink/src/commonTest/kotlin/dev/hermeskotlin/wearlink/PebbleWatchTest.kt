package dev.hermeskotlin.wearlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PebbleWatchTest {

    /** The watch's side: what it was sent, its copy of the reply as the watchapp keeps it, and what each send returns. */
    private class FakeWatch {
        val sent = mutableListOf<PebbleDict>()
        var text = ""
        val answers = ArrayDeque<PebbleSent>()
        val toldElsewhere = mutableListOf<ReplyUpdate>()

        fun receive(dict: PebbleDict): PebbleSent {
            sent += dict
            val answer = answers.removeFirstOrNull() ?: PebbleSent.Sent
            if (answer == PebbleSent.Sent && dict[PebbleKeys.TYPE] == PebbleValue.Num(PebbleTypes.REPLY)) {
                val offset = (dict[PebbleKeys.TEXT_OFFSET] as PebbleValue.Num).value
                val chunk = (dict[PebbleKeys.TEXT] as PebbleValue.Text).value
                check(offset <= utf8Size(text)) { "told to add past its text" }
                text = text.substring(0, charIndexOfUtf8(text, offset)) + chunk
            }
            return answer
        }

        val replies get() = sent.filter { it[PebbleKeys.TYPE] == PebbleValue.Num(PebbleTypes.REPLY) }
    }

    /** A watch and its sender, on the test's clock; the sender stops with the test. */
    private fun pebbleTest(body: suspend TestScope.(FakeWatch, PebbleWatch) -> Unit) = runTest {
        val fake = FakeWatch()
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            body(fake, PebbleWatch(scope, { fake.receive(it) }, gapMs = 100, retryMs = 10, finalRetryMs = 1_000, onUndelivered = { fake.toldElsewhere += it }))
        } finally {
            scope.cancel()
        }
    }

    private fun update(seq: Int, text: String, phase: ReplyPhase = ReplyPhase.Working, askId: String = "p1", activity: String? = null) =
        ReplyUpdate(askId, phase, seq = seq, text = text, activity = activity)

    private val PebbleDict.offset get() = (this[PebbleKeys.TEXT_OFFSET] as PebbleValue.Num).value
    private val PebbleDict.chunk get() = (this[PebbleKeys.TEXT] as PebbleValue.Text).value

    @Test
    fun aGrowingReplyGoesAsTheTextTheWatchLacks() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Hello"))
        advanceUntilIdle()
        watch.reply(update(2, "Hello there"))
        advanceUntilIdle()
        watch.reply(update(3, "Hello there", ReplyPhase.Done))
        advanceUntilIdle()

        assertEquals(listOf(0 to "Hello", 5 to " there", 11 to ""), fake.replies.map { it.offset to it.chunk })
        assertEquals(PebbleValue.Num(ReplyPhase.Done.ordinal), fake.replies.last()[PebbleKeys.PHASE])
        assertEquals("Hello there", fake.text)
    }

    @Test
    fun anUpdateThatChangesNothingTheWatchShowsIsntSent() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Hi", activity = "Running terminal"))
        advanceUntilIdle()
        watch.reply(update(2, "Hi", activity = "Running terminal"))
        advanceUntilIdle()
        assertEquals(1, fake.replies.size)
    }

    @Test
    fun aRewrittenReplyReplacesTheWatchsFromTheStart() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Draft one"))
        advanceUntilIdle()
        watch.reply(update(2, "Final"))
        advanceUntilIdle()
        assertEquals(0, fake.replies.last().offset)
        assertEquals("Final", fake.text)
    }

    @Test
    fun aLongReplyGoesInChunksThatEachFit() = pebbleTest { fake, watch ->
        val long = "word ".repeat(700).trim()
        watch.reply(update(1, long, ReplyPhase.Done))
        advanceUntilIdle()
        assertTrue(fake.replies.size >= 3, "${fake.replies.size} messages")
        assertTrue(fake.replies.all { PebbleLink.size(it) <= PebbleLink.WATCH_INBOX_BYTES })
        assertEquals(long, fake.text)
    }

    @Test
    fun theNewestUpdateWinsOverOnesNotYetSent() = pebbleTest { fake, watch ->
        watch.reply(update(1, "a"))
        watch.reply(update(3, "abc"))
        watch.reply(update(2, "ab"))
        advanceUntilIdle()
        assertEquals(listOf("abc"), fake.replies.map { it.chunk })
    }

    @Test
    fun aLostChunkIsSentAgainFromWhereTheWatchStands() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Hello"))
        advanceUntilIdle()
        // Every try at the next one fails; the one after still starts at what the watch acknowledged.
        repeat(3) { fake.answers.addLast(PebbleSent.Failed) }
        watch.reply(update(2, "Hello there"))
        advanceUntilIdle()
        watch.reply(update(3, "Hello there, you"))
        advanceUntilIdle()
        assertEquals(5, fake.replies.last().offset)
        assertEquals("Hello there, you", fake.text)
    }

    @Test
    fun whileTheAppIsClosedNothingGoesAndOpeningItSendsTheReplyAgain() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Hello"))
        advanceUntilIdle()
        fake.answers.addLast(PebbleSent.NotOpen)
        watch.reply(update(2, "Hello there"))
        advanceUntilIdle()
        val tries = fake.sent.size
        watch.reply(update(3, "Hello there!", ReplyPhase.Done))
        watch.message(mapOf(PebbleKeys.TYPE to PebbleValue.Num(PebbleTypes.STATUS)))
        advanceUntilIdle()
        assertEquals(tries, fake.sent.size)

        // Reopened, the watchapp starts empty.
        fake.text = ""
        watch.opened()
        advanceUntilIdle()
        assertEquals(0, fake.replies.last().offset)
        assertEquals("Hello there!", fake.text)
    }

    @Test
    fun itKnowsWhetherTheAppIsOpen() = pebbleTest { fake, watch ->
        assertTrue(watch.isOpen)
        fake.answers.addLast(PebbleSent.NotOpen)
        watch.reply(update(1, "Hello"))
        advanceUntilIdle()
        assertEquals(false, watch.isOpen)
        watch.opened()
        advanceUntilIdle()
        assertTrue(watch.isOpen)
        watch.closed()
        advanceUntilIdle()
        assertEquals(false, watch.isOpen)
    }

    @Test
    fun aResyncSendsTheReplyAgainFromTheStart() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Hello", ReplyPhase.Done))
        advanceUntilIdle()
        fake.text = ""
        watch.resync("p1")
        advanceUntilIdle()
        assertEquals(0, fake.replies.last().offset)
        assertEquals("Hello", fake.text)
    }

    @Test
    fun otherMessagesGoFirstInOrder() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Hi"))
        watch.message(mapOf(PebbleKeys.TYPE to PebbleValue.Num(PebbleTypes.STATUS)))
        watch.message(mapOf(PebbleKeys.TYPE to PebbleValue.Num(PebbleTypes.CHAT_ITEM)))
        advanceUntilIdle()
        assertEquals(
            listOf(PebbleTypes.STATUS, PebbleTypes.CHAT_ITEM, PebbleTypes.REPLY),
            fake.sent.map { (it[PebbleKeys.TYPE] as PebbleValue.Num).value },
        )
    }

    @Test
    fun aNewAskStartsTheWatchsTextOver() = pebbleTest { fake, watch ->
        watch.reply(update(5, "Old answer", ReplyPhase.Done))
        advanceUntilIdle()
        watch.reply(update(1, "", ReplyPhase.Sending, askId = "p2"))
        advanceUntilIdle()
        val last = fake.replies.last()
        assertEquals(PebbleValue.Text("p2"), last[PebbleKeys.ASK_ID])
        assertEquals(0, last.offset)
        assertEquals("", fake.text)
    }

    @Test
    fun theTurnsLastUpdateIsTriedAgainWhenLost() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Hello"))
        advanceUntilIdle()
        // A whole round of tries at Done fails; nothing else will come to carry it.
        repeat(3) { fake.answers.addLast(PebbleSent.Failed) }
        watch.reply(update(2, "Hello", ReplyPhase.Done))
        advanceUntilIdle()
        assertEquals(PebbleValue.Num(ReplyPhase.Done.ordinal), fake.replies.last()[PebbleKeys.PHASE])
        assertEquals(5, fake.replies.size)
    }

    @Test
    fun aLostUpdateMidTurnWaitsForTheNext() = pebbleTest { fake, watch ->
        repeat(3) { fake.answers.addLast(PebbleSent.Failed) }
        watch.reply(update(1, "Hello"))
        advanceUntilIdle()
        assertEquals(3, fake.replies.size)
    }

    @Test
    fun aStoppedTurnEndsAsDoneWithWhatCameSoFar() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Half an ans", activity = "Running terminal"))
        advanceUntilIdle()
        watch.stopped("p1")
        advanceUntilIdle()
        val last = fake.replies.last()
        assertEquals(PebbleValue.Num(ReplyPhase.Done.ordinal), last[PebbleKeys.PHASE])
        assertEquals(null, last[PebbleKeys.ACTIVITY])
        // Reopened later, it's still over.
        fake.text = ""
        watch.opened()
        advanceUntilIdle()
        assertEquals(PebbleValue.Num(ReplyPhase.Done.ordinal), fake.replies.last()[PebbleKeys.PHASE])
        assertEquals("Half an ans", fake.text)
    }

    @Test
    fun aTurnThatEndsWhileTheAppIsClosedIsToldElsewhereOnce() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Working on it"))
        advanceUntilIdle()
        watch.closed()
        watch.reply(update(2, "All done", ReplyPhase.Done))
        watch.reply(update(3, "All done", ReplyPhase.Done))
        advanceUntilIdle()
        assertEquals(listOf("All done"), fake.toldElsewhere.map { it.text })
    }

    @Test
    fun aTurnThatEndsJustAsTheAppClosesIsToldElsewhere() = pebbleTest { fake, watch ->
        watch.reply(update(1, "Working on it"))
        advanceUntilIdle()
        fake.answers.addLast(PebbleSent.NotOpen)
        watch.reply(update(2, "All done", ReplyPhase.Done))
        advanceUntilIdle()
        assertEquals(1, fake.toldElsewhere.size)
    }

    @Test
    fun aTurnTheWatchSawEndOrStoppedIsntToldElsewhere() = pebbleTest { fake, watch ->
        watch.reply(update(1, "All done", ReplyPhase.Done))
        advanceUntilIdle()
        watch.closed()
        advanceUntilIdle()
        watch.opened()
        watch.reply(update(1, "Half", askId = "p2"))
        advanceUntilIdle()
        watch.stopped("p2")
        watch.closed()
        advanceUntilIdle()
        assertEquals(emptyList(), fake.toldElsewhere)
    }
}

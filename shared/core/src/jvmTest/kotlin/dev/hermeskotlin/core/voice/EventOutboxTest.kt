package dev.hermeskotlin.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventOutboxTest {
    private val sent = mutableListOf<String>()
    private val failures = mutableListOf<Throwable>()
    private val pending = ArrayDeque<Runnable>()
    private var open = true
    private var broken: Throwable? = null
    private val outbox = EventOutbox(Any(), { bytes ->
        broken?.let { throw it }
        if (open) sent += bytes.decodeToString()
        open
    }, { failures += it }, { pending.addLast(it) })

    @Test fun aReplyFromInsideACallbackKeepsItsPlaceBeforeLaterSends() {
        assertTrue(outbox.send("reply".encodeToByteArray(), deferred = true))
        assertTrue(sent.isEmpty(), "A deferred event must not go out on the callback's thread")
        assertTrue(outbox.send("next".encodeToByteArray(), deferred = false))
        pending.removeFirst().run()
        assertEquals(listOf("reply", "next"), sent)
        assertTrue(failures.isEmpty())
    }

    @Test fun aDeferredEventThatCantGoOutFailsTheCall() {
        assertTrue(outbox.send("reply".encodeToByteArray(), deferred = true))
        open = false
        pending.removeFirst().run()
        assertEquals(1, failures.size)
    }

    @Test fun aDirectSendBeforeTheChannelOpensReturnsFalseAndTheCallGoesOn() {
        open = false
        assertFalse(outbox.send("early".encodeToByteArray(), deferred = false))
        assertTrue(failures.isEmpty())
        open = true
        assertTrue(outbox.send("later".encodeToByteArray(), deferred = false))
        assertEquals(listOf("later"), sent)
    }

    @Test fun aFailedSendFailsTheCallAndTellsItsSender() {
        broken = IllegalStateException("Synthetic full queue")
        assertFalse(outbox.send("event".encodeToByteArray(), deferred = false))
        assertTrue(failures.single() === broken)
    }

    @Test fun aSchedulerThatRefusesFailsTheCall() {
        val refusing = EventOutbox(Any(), { true }, { failures += it }, { throw IllegalStateException("Synthetic shutdown") })
        assertFalse(refusing.send("reply".encodeToByteArray(), deferred = true))
        assertEquals(1, failures.size)
    }
}

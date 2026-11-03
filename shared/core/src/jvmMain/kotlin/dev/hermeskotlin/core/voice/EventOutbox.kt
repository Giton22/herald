package dev.hermeskotlin.core.voice

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A call's outgoing events, in the order they were sent. A direct send goes out at once and learns whether it did; a
 * deferred one (made where native calls mustn't block) is told yes and goes out later, so if it then can't, the call
 * fails rather than lose it.
 */
internal class EventOutbox(
    private val lock: Any,
    /** False while the channel can't take events yet; throws when the call can't go on. */
    private val transmit: (ByteArray) -> Boolean,
    private val fail: (Throwable) -> Unit,
    private val later: (Runnable) -> Unit,
) {
    private class Outgoing(val bytes: ByteArray, val deferred: Boolean) { @Volatile var sent = false }
    private val queue = ConcurrentLinkedQueue<Outgoing>()

    fun send(bytes: ByteArray, deferred: Boolean): Boolean {
        val item = Outgoing(bytes, deferred)
        queue.add(item)
        if (!deferred) {
            // Sends everything queued before it too; another thread's flush may have sent this one already.
            flush()
            return item.sent
        }
        try { later(Runnable { flush() }) } catch (caught: Throwable) {
            fail(IllegalStateException("The call couldn't send an event.", caught)); return false
        }
        return true
    }

    private fun flush() {
        var failure: Throwable? = null
        synchronized(lock) {
            while (failure == null) {
                val item = queue.poll() ?: break
                try {
                    item.sent = transmit(item.bytes)
                    if (!item.sent && item.deferred) failure = IllegalStateException("The call couldn't send an event.")
                } catch (caught: Throwable) { failure = caught }
            }
        }
        failure?.let(fail)
    }
}

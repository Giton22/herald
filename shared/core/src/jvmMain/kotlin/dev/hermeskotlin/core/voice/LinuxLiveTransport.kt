package dev.hermeskotlin.core.voice

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface LivePeerEvents {
    /** A bounded owned copy of the readable window; false asks the native peer to retire. */
    fun message(bytes: ByteArray, binary: Boolean): Boolean
    fun ended(failure: Throwable?)
}
internal interface LivePeer : AutoCloseable {
    /** Begins retirement without blocking cancellation; close waits for the owned release. */
    fun retire()
    suspend fun offer(): String
    suspend fun answer(sdp: String)
    fun send(bytes: ByteArray): Boolean
}

/** Signaling and events only; a LiveCall supplies real media separately. One connection per instance. */
internal class LinuxLiveTransport(private val makePeer: (LivePeerEvents) -> LivePeer) : AutoCloseable {
    private val owner = Any()
    private var peer: LivePeer? = null
    private val started = AtomicBoolean()
    private val terminal = AtomicReference<Throwable?>()
    private val ended = CompletableDeferred<Throwable>()
    private val releaseReported = AtomicBoolean()
    // A reply streams one event per transcript fragment: a short pause in the consumer mustn't end the call.
    private val incoming = Channel<String>(MAX_QUEUED_EVENTS)
    val events: ReceiveChannel<String> = incoming
    private val listener = object : LivePeerEvents {
        override fun message(bytes: ByteArray, binary: Boolean): Boolean {
            if (terminal.get() != null) return false
            if (binary || bytes.size > MAX_EVENT_BYTES) {
                ended(IllegalStateException("The call received an unsupported event.")); return false
            }
            val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
            catch (_: CharacterCodingException) {
                ended(IllegalStateException("The call received an invalid text event.")); return false
            }
            val sent = incoming.trySend(text)
            if (sent.isFailure && !sent.isClosed) ended(IllegalStateException("The call's event queue is full."))
            return sent.isSuccess
        }
        override fun ended(failure: Throwable?) {
            val reason = failure ?: IllegalStateException("The call ended.")
            if (terminal.compareAndSet(null, reason)) {
                incoming.close(failure)
                if (failure != null) while (incoming.tryReceive().isSuccess) { }
                ended.complete(reason)
            }
        }
    }

    suspend fun connect(answer: suspend (String) -> String) {
        check(started.compareAndSet(false, true)) { "This call has already started." }
        val caller = currentCoroutineContext()
        try {
            terminal.get()?.let { throw it }
            withTimeout(60_000) {
                coroutineScope {
                    val connected = AtomicBoolean()
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally {
                            if (!connected.get()) {
                                if (!caller.isActive) {
                                    terminal.compareAndSet(null, CancellationException("The call was cancelled."))
                                    incoming.cancel()
                                    ended.complete(checkNotNull(terminal.get()))
                                }
                                val owned = synchronized(owner) { peer }
                                owned?.retire()
                            }
                        }
                    }
                    try {
                        val exchange = async {
                            val owned = withContext(Dispatchers.IO) {
                                val created = makePeer(listener)
                                val accepted = synchronized(owner) {
                                    if (terminal.get() != null) false else { peer = created; true }
                                }
                                if (!accepted) {
                                    val reason = checkNotNull(terminal.get())
                                    try { created.close() } catch (cleanup: Throwable) { if (reason !== cleanup) reason.addSuppressed(cleanup) }
                                    throw reason
                                }
                                created
                            }
                            terminal.get()?.let { throw it }
                            val offer = owned.offer()
                            require(offer.isNotBlank() && offer.length <= MAX_SDP_CHARS) { "Couldn't make the call offer." }
                            // The gateway needs the offer byte-exact, including its trailing CRLF.
                            val remote = answer(offer)
                            currentCoroutineContext().ensureActive()
                            require(remote.isNotBlank() && remote.length <= MAX_SDP_CHARS) { "The call returned an invalid answer." }
                            terminal.get()?.let { throw it }
                            owned.answer(remote)
                            terminal.get()?.let { throw it }
                        }
                        select<Unit> {
                            ended.onAwait { throw it }
                            exchange.onAwait { }
                        }
                        connected.set(true)
                    } finally { cancellation.cancelAndJoin() }
                }
            }
        } catch (caught: Throwable) {
            val callerCancellation = try { currentCoroutineContext().ensureActive(); null }
                catch (cancelled: CancellationException) { cancelled }
            val primary = callerCancellation ?: terminal.get() ?: caught
            // Only the caller's own cancellation stays one: a close from elsewhere is a failure the caller must see,
            // not a CancellationException its still-active coroutine would quietly swallow.
            val failure = callerCancellation
                ?: IllegalStateException(if (primary is TimeoutCancellationException) "The call took too long to connect." else primary.message, primary)
            try { withContext(NonCancellable + Dispatchers.IO) { close() } }
            catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun send(event: String): Boolean {
        if (terminal.get() != null) return false
        val bytes = if (event.length <= MAX_EVENT_BYTES) event.encodeToByteArray() else null
        if (bytes == null || bytes.size > MAX_EVENT_BYTES) {
            listener.ended(IllegalStateException("The call's outgoing event is too large."))
            // Send runs on the caller's thread: start the release, and leave waiting for it to the owner's close.
            synchronized(owner) { peer }?.retire()
            return false
        }
        // The peer serializes its own native lifetime; never hold this publication lock across a native call.
        val owned = synchronized(owner) { if (terminal.get() == null) peer else null }
        return owned?.send(bytes) == true
    }

    private fun releasePeer() {
        // Retain the single-use peer so every owner close joins the same native retirement.
        val owned = synchronized(owner) { peer }
        try { owned?.close() } catch (failure: Throwable) {
            // Report a release failure once: connect's own cleanup usually has, and a caller's
            // `finally { close() }` throwing it again would replace connect's error.
            if (releaseReported.compareAndSet(false, true)) throw failure
        }
    }

    override fun close() {
        terminal.compareAndSet(null, CancellationException("The call was closed."))
        incoming.cancel()
        ended.complete(checkNotNull(terminal.get()))
        releasePeer()
    }

    companion object {
        const val MAX_EVENT_BYTES = 1024 * 1024
        const val MAX_QUEUED_EVENTS = 1024
        private const val MAX_SDP_CHARS = 1024 * 1024
    }
}

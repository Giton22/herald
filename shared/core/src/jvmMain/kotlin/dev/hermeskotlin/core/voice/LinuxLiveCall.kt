package dev.hermeskotlin.core.voice

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface LiveMediaPeer : LivePeer {
    fun setMuted(muted: Boolean)
    fun micLevel(): Float
    fun speaking(): Boolean
}

internal class LinuxLiveCall(makePeer: (LivePeerEvents) -> LiveMediaPeer) : LiveCall {
    private val closed = AtomicBoolean()
    private val media = AtomicReference<LiveMediaPeer?>()
    private val muteOwner = Any()
    private var muted = false
    private val transport = LinuxLiveTransport { events ->
        val created = makePeer(events)
        try {
            synchronized(muteOwner) {
                if (!closed.get()) {
                    created.setMuted(muted)
                    media.set(created)
                }
            }
            if (closed.get()) media.compareAndSet(created, null)
            created
        } catch (failure: Throwable) {
            try { created.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override val events get() = transport.events
    override suspend fun connect(answer: suspend (String) -> String) = transport.connect(answer)
    override fun send(event: String) = transport.send(event)
    override fun setMuted(muted: Boolean) = synchronized(muteOwner) {
        if (!closed.get()) {
            this.muted = muted
            media.get()?.setMuted(muted)
        }
    }
    override fun micLevel() = if (closed.get()) 0f else media.get()?.micLevel() ?: 0f
    override fun speaking() = !closed.get() && media.get()?.speaking() == true
    override fun close() {
        closed.set(true)
        media.set(null)
        // Never hold the mute lock while joining native shutdown: a callback may end this call.
        transport.close()
    }
}

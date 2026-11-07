package dev.hermeskotlin.core.voice

internal class LiveAudioLevels(private val now: () -> Long = System::nanoTime) {
    private var closed = false
    private var muted = false
    private var level = 0f
    private var sampleAt: Long? = null
    private var loudAt: Long? = null

    @Synchronized fun update(microphone: Double?, playback: Double?) {
        if (closed) return
        val time = now()
        sampleAt = time
        level = if (muted) 0f else validLevel(microphone)
        if (validLevel(playback) > .02f) loudAt = time
    }

    @Synchronized fun mute(muted: Boolean) { this.muted = muted; level = 0f }
    @Synchronized fun close() { closed = true; level = 0f; loudAt = null }
    @Synchronized fun micLevel(): Float = if (!closed && !muted && fresh(sampleAt, 500_000_000L)) level else 0f
    @Synchronized fun speaking(): Boolean = !closed && fresh(loudAt, 400_000_000L)

    private fun fresh(at: Long?, limit: Long): Boolean = at != null && (now() - at) in 0 until limit
    private fun validLevel(value: Double?): Float = if (value?.isFinite() == true) value.coerceIn(0.0, 1.0).toFloat() else 0f
}

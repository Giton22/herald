package dev.hermeskotlin.core.voice

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The voice made for replies read aloud, kept so a reply read again plays the same take from its start rather
 * than a new one: a voice like ElevenLabs says the same text differently each time. Kept for each [reply] (the
 * gateway, profile and text) by the words of each [piece], so a piece only ever plays for the words it says.
 */
interface ReadAloudVoices {
    suspend fun piece(reply: String, piece: String): SpokenAudio?

    suspend fun keep(reply: String, piece: String, audio: SpokenAudio)
}

/**
 * Kept while the app runs: the voices of the last [replies] replies read aloud. A take older than [maxAge] is voiced
 * anew, so a voice changed on the gateway is heard by the next day even while the app stays open.
 */
class InMemoryReadAloudVoices(
    private val replies: Int = 5,
    private val maxAge: Duration = 1.days,
    private val time: TimeSource = TimeSource.Monotonic,
) : ReadAloudVoices {
    private class Take(val audio: SpokenAudio, val made: TimeMark)

    private val mutex = Mutex()
    private val voices = LinkedHashMap<String, MutableMap<String, Take>>()

    override suspend fun piece(reply: String, piece: String): SpokenAudio? = mutex.withLock {
        // Only a reply that's kept moves up; looking for one that isn't lets nothing go.
        voices.remove(reply)?.also { voices[reply] = it }?.get(piece)?.takeIf { it.made.elapsedNow() <= maxAge }?.audio
    }

    override suspend fun keep(reply: String, piece: String, audio: SpokenAudio) = keep(reply, piece, audio, Duration.ZERO)

    /** Keeps a take made [age] ago, one read back from the disk, say, so it's let go when it would have been. */
    suspend fun keep(reply: String, piece: String, audio: SpokenAudio, age: Duration) = mutex.withLock {
        val pieces = voices.remove(reply) ?: mutableMapOf()
        pieces[piece] = Take(audio, time.markNow() - age)
        voices[reply] = pieces
        while (voices.size > replies) voices.remove(voices.keys.first())
    }
}

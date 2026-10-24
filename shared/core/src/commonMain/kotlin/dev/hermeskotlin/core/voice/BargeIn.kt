package dev.hermeskotlin.core.voice

import kotlin.math.max
import kotlin.math.min

/**
 * Talking over a reply to cut in (the profile's `voice.barge_in`, on unless it's set off). [multiplier] is
 * `voice.barge_in_threshold_multiplier`, the sensitivity: lower trips sooner; null keeps the stock levels.
 * [graceMs] is `voice.barge_in_grace_seconds`, how long the voice's first sound can't trip it.
 */
data class BargeInSettings(
    val enabled: Boolean = true,
    val multiplier: Double? = null,
    val graceMs: Long = DEFAULT_GRACE_MS,
) {
    companion object {
        const val DEFAULT_GRACE_MS = 500L
    }
}

/**
 * Decides, frame by frame, when someone talks over the agent's turn, and then when they've finished: Desktop's
 * phase-aware trigger (voice-barge-in.ts, after tools/voice_mode.full_duplex_listen).
 *
 * - The room's quiet floor is measured only while nothing plays, and held while the voice plays: measured over
 *   the speaker, its bleed would sit in the floor and the bar would be out of reach.
 * - While the voice plays, the bar is raised so its bleed alone can't trip it, and capped so speech still can.
 * - Unlike Desktop, it also learns how loud the voice actually comes back (its louder syllables) and keeps the bar
 *   clearly above that, so a loudspeaker the phone can't cancel doesn't cut in on itself, however loud it's
 *   turned up. Over that echo, speech has to hold longer ([SUSTAINED_OVER_ECHO_MS]): the voice's own syllables
 *   rise and fall, someone talking over it doesn't. Someone already talking as the voice starts isn't learnt as
 *   its echo, since echo can't come before the voice; nor is someone talking a little under the bar, which would
 *   raise it out of their reach the longer they tried. However loud the echo, the bar stays where speech can reach
 *   it ([ECHO_BAR_CEILING_LEVEL]): an echo that trips it only holds the voice for a moment.
 * - The voice's first moment after a real gap can't trip it ([BargeInSettings.graceMs]).
 * - Speech has to hold: most of the last [SUSTAINED_MS] above the bar, so dips inside a word don't start it over.
 *   Over a loud echo, most of the last [SUSTAINED_OVER_ECHO_MS].
 *
 * Once tripped, the voice holds and the utterance runs until a pause of [silenceMs] or [UTTERANCE_MAX_MS]. Whether
 * anyone went on talking once it held ([spoke]) tells someone cutting in from the phone hearing its own voice,
 * whose echo stops with it; when nobody did, it's over after [NOBODY_AFTER_MS] rather than a whole pause.
 *
 * The levels are the recorder's (RMS scaled so 1.0 is loud speech). They sit below Desktop's: the phone's
 * call microphone is quieter than a browser's, and its echo cancelling takes most of the voice out already.
 */
class BargeIn(
    private val settings: BargeInSettings = BargeInSettings(),
    private val silenceMs: Long = 1_250,
    /** The room's quiet floor as an earlier listen in the same chat left it ([roomFloor]); 0 when none has. */
    roomFloor: Float = 0f,
) {
    private val scale = settings.multiplier?.takeIf { it.isFinite() && it > 0 }?.let { it / DEFAULT_MULTIPLIER } ?: 1.0
    private val floorMultiplier = (FLOOR_MULTIPLIER * scale).toFloat()
    private val playbackMin = (PLAYBACK_MIN_LEVEL * scale).toFloat()
    // A lower sensitivity setting still keeps the bar over the voice's own echo.
    private val bleedMultiplier = max(BLEED_MULTIPLIER * scale, MIN_BLEED_MARGIN).toFloat()

    var tripped = false
        private set

    private val floorSamples = Levels(FLOOR_SAMPLES)
    private var floor = 0f

    init {
        // A listen that starts over the voice, as after it held and went on, can't measure the room itself.
        if (roomFloor > 0f) addFloor(roomFloor)
    }

    /** The room's quiet floor so far, for the next listen in the chat; 0 when it hasn't heard the room quiet. */
    val roomFloor: Float get() = floor
    private val bleedSamples = Levels(FLOOR_SAMPLES)
    private var bleed = 0f
    private var calibratedSince = -1L
    private var floorLocked = false
    private var wasPlaying = false
    private var playbackSeen = false
    private var lastPlayingAt = 0L
    private var graceUntil = 0L
    /** The voice's first moment is learnt as its echo; not when someone was already talking as it started. */
    private var graceIsEcho = true
    /** Whether each recent frame stood above the bar, and when it came; the last [SUSTAINED_OVER_ECHO_MS] of them. */
    private val recent = ArrayDeque<Pair<Long, Boolean>>()
    private var trippedAt = 0L
    private var quietSince = -1L
    private var spokeFrames = 0

    /** Someone went on talking once the voice held, past its last echo ([HOLD_SETTLE_MS]); not just the voice. */
    var spoke = false
        private set

    /** Feeds one frame's [level] at [nowMs], with the voice [playing] or not; how things stand after it. */
    fun onFrame(level: Float, nowMs: Long, playing: Boolean): Step {
        if (tripped) return endpoint(level, nowMs)
        if (!floorLocked) {
            if (!playing) {
                if (calibratedSince < 0) calibratedSince = nowMs
                addFloor(level)
            }
            if (playing || calibratedSince >= 0 && nowMs - calibratedSince >= CALIBRATION_MS) floorLocked = true
        }
        // A grace only when the voice starts after a real gap, so it flickering between sentences can't chain them.
        if (playing && !wasPlaying) {
            if (!playbackSeen || nowMs - lastPlayingAt >= PLAYBACK_GAP_FOR_GRACE_MS) {
                graceUntil = nowMs + settings.graceMs
                graceIsEcho = !talkingAt(nowMs)
            }
            playbackSeen = true
        }
        wasPlaying = playing
        if (playing) lastPlayingAt = nowMs

        val trigger = trigger(playing)
        // The room drifts; follow it while it's quiet.
        if (floorLocked && !playing && level < trigger) addFloor(level)
        // How loud the voice comes back through the microphone, which echo cancelling may or may not have taken out.
        // Learnt from all of its first moment (nothing can trip it then), and after that from what stays under the bar.
        // Not from a frame well over the echo learnt so far, nor while anything stood over the bar: that's someone
        // talking just under it.
        val inGrace = nowMs < graceUntil
        if (playing && (inGrace && graceIsEcho || !inGrace && level < trigger && level <= bleed * BLEED_LEARN_HEADROOM &&
                recent.none { it.second })) addBleed(level)
        val above = floorLocked && level >= trigger && !inGrace
        recent.addLast(nowMs to above)
        while (recent.isNotEmpty() && nowMs - recent.first().first > SUSTAINED_OVER_ECHO_MS) recent.removeFirst()
        // Over a loud echo its syllables can poke above the bar; someone talking stays there.
        val hold = if (playing && trigger > playingBar()) SUSTAINED_OVER_ECHO_MS else SUSTAINED_MS
        // Counted in place, every frame: the frames within the hold, when the first of them came, and those above the bar.
        var since = nowMs
        var frames = 0
        var aboveCount = 0
        for ((at, over) in recent) {
            if (nowMs - at > hold) continue
            if (frames++ == 0) since = at
            if (over) aboveCount++
        }
        val span = nowMs - since
        if (above && span >= hold * SUSTAINED_MAJORITY && aboveCount >= frames * SUSTAINED_MAJORITY) {
            tripped = true
            trippedAt = nowMs
            return Step.Tripped
        }
        return Step.Waiting
    }

    /** The bar speech has to clear now. */
    internal fun trigger(playing: Boolean): Float {
        if (!playing) return quietBar()
        // Over the voice: above its own echo, so speech has to stand clearly over it; but never out of speech's reach.
        return max(playingBar(), min(bleed * bleedMultiplier, ECHO_BAR_CEILING_LEVEL))
    }

    private fun quietBar() = max(MIN_TRIGGER_LEVEL, floor * floorMultiplier)

    /** The bar over the voice before its echo is counted: raised for its bleed, capped so speech still clears it. */
    private fun playingBar() = min(max(quietBar(), playbackMin), TRIGGER_CEILING_LEVEL)

    /** Someone was talking just before [nowMs]: most of the last [TALKING_MS] stood over the bar. */
    private fun talkingAt(nowMs: Long): Boolean {
        var frames = 0
        var aboveCount = 0
        for ((at, over) in recent) {
            if (nowMs - at > TALKING_MS) continue
            frames++
            if (over) aboveCount++
        }
        return frames >= 3 && aboveCount >= frames * SUSTAINED_MAJORITY
    }

    /**
     * After the trip the voice holds, so a plain pause ends the utterance. Someone went on talking only as loud as
     * could have cut in over the voice: the room's hum isn't them.
     */
    private fun endpoint(level: Float, nowMs: Long): Step {
        if (nowMs - trippedAt >= HOLD_SETTLE_MS && level >= playingBar() && ++spokeFrames >= SPOKE_FRAMES) spoke = true
        if (level >= max(MIN_TRIGGER_LEVEL * QUIET_FRACTION, floor * QUIET_OVER_FLOOR)) quietSince = -1
        else if (quietSince < 0) quietSince = nowMs
        // Nobody went on once the voice held: it was the phone hearing itself, or a knock.
        if (!spoke && nowMs - trippedAt >= NOBODY_AFTER_MS) return Step.Done
        val done = quietSince >= 0 && nowMs - quietSince >= silenceMs || nowMs - trippedAt >= UTTERANCE_MAX_MS
        return if (done) Step.Done else Step.Hearing
    }

    private fun addBleed(level: Float) {
        bleedSamples.add(level)
        // Its louder syllables, not its average: the voice's peaks are what could pass for someone talking.
        bleed = bleedSamples[(bleedSamples.size * BLEED_PERCENTILE).toInt().coerceAtMost(bleedSamples.size - 1)]
    }

    private fun addFloor(level: Float) {
        floorSamples.add(level)
        floor = floorSamples[floorSamples.size / 2]
    }

    /** The last [capacity] levels, kept sorted as they come, so a percentile needs no sort on every frame. */
    private class Levels(private val capacity: Int) {
        private val arrived = ArrayDeque<Float>()
        private val sorted = ArrayList<Float>()
        val size: Int get() = sorted.size

        fun add(level: Float) {
            arrived.addLast(level)
            sorted.add(slot(level), level)
            if (arrived.size > capacity) sorted.removeAt(slot(arrived.removeFirst()))
        }

        operator fun get(index: Int): Float = sorted[index]

        /** Where [level] is, or goes. */
        private fun slot(level: Float) = sorted.binarySearch(level).let { if (it < 0) -it - 1 else it }
    }

    enum class Step {
        /** Nobody has cut in. */
        Waiting,
        /** Someone just started talking over it: cut the voice and the turn now. */
        Tripped,
        /** They're still talking. */
        Hearing,
        /** They've finished; the utterance is complete. */
        Done,
    }

    companion object {
        /** tools/voice_mode.DEFAULT_BARGE_MULTIPLIER; the levels here are tuned for it. */
        const val DEFAULT_MULTIPLIER = 3.0
        const val CALIBRATION_MS = 400L
        const val SUSTAINED_MS = 300L
        const val SUSTAINED_OVER_ECHO_MS = 500L
        const val TALKING_MS = 100L
        const val SUSTAINED_MAJORITY = 0.8f
        const val MIN_TRIGGER_LEVEL = 0.025f
        const val FLOOR_MULTIPLIER = 3.5
        const val PLAYBACK_MIN_LEVEL = 0.05
        const val BLEED_MULTIPLIER = 1.8
        /** However sensitive it's set, the bar stays this far over the voice's echo. */
        const val MIN_BLEED_MARGIN = 1.2
        const val BLEED_PERCENTILE = 0.8f
        const val TRIGGER_CEILING_LEVEL = 0.25f
        /** The highest the bar goes over a loud echo; past it the echo trips it, and the voice only holds a moment. */
        const val ECHO_BAR_CEILING_LEVEL = 0.4f
        /** After the voice's first moment, its echo is learnt only from frames this close to what's learnt already. */
        const val BLEED_LEARN_HEADROOM = 1.25f
        const val PLAYBACK_GAP_FOR_GRACE_MS = 1_000L
        /** Three seconds of 20 ms frames. */
        const val FLOOR_SAMPLES = 150
        const val QUIET_FRACTION = 0.8f
        const val QUIET_OVER_FLOOR = 1.6f
        const val UTTERANCE_MAX_MS = 30_000L
        /**
         * How much sound before the trip is kept, so the utterance has its first syllable: the longest it takes to
         * trip, and a little more. Not longer, since what's before it is the voice's own words.
         */
        const val PRE_ROLL_MS = 800L
        /** How long the voice's echo can go on after it holds: the speaker's buffer, and the room. */
        const val HOLD_SETTLE_MS = 300L
        /** How much talking, past that, says someone is there. */
        const val SPOKE_FRAMES = 8
        /** When nobody has gone on talking by then, the trip was the voice's own echo, or a knock. */
        const val NOBODY_AFTER_MS = 900L
    }
}

/** tools/voice_mode_transcript.DEFAULT_TTS_ECHO_SIMILARITY_THRESHOLD */
private const val ECHO_SIMILARITY = 0.6
/** Shorter than this, a real one-word cut-in ("yes") would match some window of the reply. */
private const val MIN_ECHO_FRAGMENT = 10

/**
 * Whether [transcript] is the phone hearing its own voice rather than someone cutting in: close enough to
 * [spoken], the reply that was playing when it tripped, or to a stretch of it its own length. Desktop's
 * isTtsEcho, after tools/voice_mode_transcript.is_tts_echo.
 */
fun isTtsEcho(transcript: String, spoken: String): Boolean {
    val a = echoForm(transcript)
    val b = echoForm(spoken)
    if (a.isEmpty() || b.isEmpty()) return false
    if (sequenceRatio(a, b) >= ECHO_SIMILARITY) return true
    if (a.size < MIN_ECHO_FRAGMENT || a.size >= b.size) return false
    // A stretch can't match better than the letters it shares with the transcript (difflib's quick_ratio), counted as
    // it slides along the reply; only a stretch that clears that is compared in full. Same answers, far less work.
    val need = HashMap<Int, Int>()
    a.forEach { need[it] = (need[it] ?: 0) + 1 }
    val have = HashMap<Int, Int>()
    var shared = 0
    fun add(x: Int) {
        val n = have[x] ?: 0
        have[x] = n + 1
        if (n < (need[x] ?: 0)) shared++
    }
    fun drop(x: Int) {
        val n = have.getValue(x)
        have[x] = n - 1
        if (n <= (need[x] ?: 0)) shared--
    }
    for (j in a.indices) add(b[j])
    for (start in 0..b.size - a.size) {
        if (start > 0) {
            drop(b[start - 1])
            add(b[start + a.size - 1])
        }
        if (shared >= a.size * ECHO_SIMILARITY && sequenceRatio(a, b.subList(start, start + a.size)) >= ECHO_SIMILARITY) return true
    }
    return false
}

/** Code points, like Python's str, lowercased with the spaces collapsed. */
private fun echoForm(text: String): List<Int> {
    val s = text.replace(Regex("""\s+"""), " ").trim().lowercase()
    val points = ArrayList<Int>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            points += ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
            i += 2
        } else {
            points += c.code
            i++
        }
    }
    return points
}

/** Python's `difflib.SequenceMatcher(None, a, b).ratio()`, autojunk and all. */
internal fun <T> sequenceRatio(a: List<T>, b: List<T>): Double {
    val total = a.size + b.size
    if (total == 0) return 1.0
    val b2j = HashMap<T, MutableList<Int>>()
    b.forEachIndexed { j, x -> b2j.getOrPut(x) { mutableListOf() } += j }
    // Elements in more than 1% of a long b never start a match, though they can extend one.
    if (b.size >= 200) {
        val ntest = b.size / 100 + 1
        b2j.entries.removeAll { it.value.size > ntest }
    }

    fun longest(alo: Int, ahi: Int, blo: Int, bhi: Int): Triple<Int, Int, Int> {
        var besti = alo
        var bestj = blo
        var best = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val next = HashMap<Int, Int>()
            for (j in b2j[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                next[j] = k
                if (k > best) {
                    besti = i - k + 1
                    bestj = j - k + 1
                    best = k
                }
            }
            j2len = next
        }
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
            besti--
            bestj--
            best++
        }
        while (besti + best < ahi && bestj + best < bhi && a[besti + best] == b[bestj + best]) best++
        return Triple(besti, bestj, best)
    }

    var matches = 0
    val queue = ArrayDeque(listOf(intArrayOf(0, a.size, 0, b.size)))
    while (queue.isNotEmpty()) {
        val (alo, ahi, blo, bhi) = queue.removeLast()
        val (i, j, k) = longest(alo, ahi, blo, bhi)
        if (k > 0) {
            matches += k
            if (alo < i && blo < j) queue.addLast(intArrayOf(alo, i, blo, j))
            if (i + k < ahi && j + k < bhi) queue.addLast(intArrayOf(i + k, ahi, j + k, bhi))
        }
    }
    return 2.0 * matches / total
}

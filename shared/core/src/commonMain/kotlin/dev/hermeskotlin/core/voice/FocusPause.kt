package dev.hermeskotlin.core.voice

/**
 * What a reply read aloud does as other audio takes the speaker: it pauses, and goes on by itself when the other
 * audio was only a moment (a navigation prompt, a call's ring) and gives the speaker back. Other audio that stays
 * (music started in another app) leaves it paused for the person to resume; one who paused it themselves keeps it
 * paused. [paused] says whether the reading is paused now.
 */
class FocusPause(private val controls: ReadAloudControls, private val paused: () -> Boolean) {

    /** Paused because the speaker was taken for a moment, so it goes on when it's given back. */
    var waiting = false
        private set

    /** The speaker was taken for good. */
    fun lostForGood() {
        waiting = false
        controls.pause()
    }

    /** The speaker was taken for a moment; a reading already paused stays the person's. */
    fun lostForAMoment() {
        if (paused()) return
        waiting = true
        controls.pause()
    }

    /** The speaker is back. */
    fun regained() {
        if (!waiting) return
        waiting = false
        controls.resume()
    }

    /** The person resumed it themselves; the speaker is theirs to ask for again, and nothing waits for it. */
    fun resumedByPerson() {
        waiting = false
    }
}

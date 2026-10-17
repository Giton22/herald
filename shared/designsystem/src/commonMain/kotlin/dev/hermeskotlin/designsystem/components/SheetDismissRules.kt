package dev.hermeskotlin.designsystem.components

/**
 * When a [BottomSheet] reports a dismiss, and when the user may close it, kept apart from Compose so it can be
 * tested. Fed every change of the sheet's position; one instance lives as long as its sheet.
 */
internal class SheetDismissRules {
    /** The sheet is somewhere other than hidden at rest: showing, sliding in or out, or being dragged. */
    private var shown = false

    /** This showing has come to rest open at least once. */
    private var settledOpen = false

    /**
     * Takes the sheet's new position and returns true when the showing that just ended needs a dismiss report:
     * the sheet came to rest hidden while [visible] still asked for it, so the user closed it. Returns true at
     * most once per showing.
     */
    fun onPosition(idle: Boolean, hidden: Boolean, visible: Boolean): Boolean {
        if (!(idle && hidden)) {
            shown = true
            if (idle) settledOpen = true
            return false
        }
        val ended = shown
        shown = false
        settledOpen = false
        return ended && visible
    }

    /**
     * Whether the sheet may move to hidden. Closing through [visible] always may; the user's tap outside or Back
     * only once the sheet has come to rest open.
     */
    fun mayHide(visible: Boolean): Boolean = !visible || settledOpen
}

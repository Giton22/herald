package dev.hermeskotlin.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SheetDismissRulesTest {
    private val rules = SheetDismissRules()

    /** Feeds the positions of a sheet sliding in and coming to rest open. */
    private fun open(visible: Boolean = true) {
        rules.onPosition(idle = true, hidden = true, visible = visible)
        rules.onPosition(idle = false, hidden = false, visible = visible)
        rules.onPosition(idle = true, hidden = false, visible = visible)
    }

    @Test
    fun aDragDownReportsOneDismiss() {
        open()
        assertTrue(rules.mayHide(visible = true))
        val reports = listOf(
            rules.onPosition(idle = false, hidden = false, visible = true),
            rules.onPosition(idle = true, hidden = true, visible = true),
            // The same rest position again, as a snapshot can show it twice, reports nothing more.
            rules.onPosition(idle = true, hidden = true, visible = true),
        )
        assertEquals(1, reports.count { it })
    }

    @Test
    fun closingThroughVisibleReportsNothing() {
        open()
        rules.onPosition(idle = false, hidden = false, visible = false)
        assertFalse(rules.onPosition(idle = true, hidden = true, visible = false))
    }

    @Test
    fun theUserCannotCloseASheetStillSlidingIn() {
        rules.onPosition(idle = true, hidden = true, visible = true)
        rules.onPosition(idle = false, hidden = false, visible = true)
        assertFalse(rules.mayHide(visible = true))
        // Closing it from the app still goes through.
        assertTrue(rules.mayHide(visible = false))
    }

    @Test
    fun aDragStaysAllowedOnceTheSheetHasSettled() {
        open()
        rules.onPosition(idle = false, hidden = false, visible = true)
        assertTrue(rules.mayHide(visible = true))
    }

    @Test
    fun eachShowingWaitsToSettleAgain() {
        open()
        rules.onPosition(idle = true, hidden = true, visible = true)
        rules.onPosition(idle = false, hidden = false, visible = true)
        assertFalse(rules.mayHide(visible = true))
    }

    @Test
    fun aSheetThatNeverShowedReportsNothing() {
        assertFalse(rules.onPosition(idle = true, hidden = true, visible = true))
    }
}

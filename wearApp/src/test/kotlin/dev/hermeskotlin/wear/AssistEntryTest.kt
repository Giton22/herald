package dev.hermeskotlin.wear

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistEntryTest {

    @Test
    fun theSystemAssistantCallListens() {
        assertTrue(AssistEntry.asksToListen(AssistEntry.ACTION_ASSIST, wantsListen = false))
    }

    @Test
    fun theTileAndComplicationListen() {
        assertTrue(AssistEntry.asksToListen("android.intent.action.MAIN", wantsListen = true))
    }

    @Test
    fun aPlainLaunchDoesNot() {
        assertFalse(AssistEntry.asksToListen("android.intent.action.MAIN", wantsListen = false))
        assertFalse(AssistEntry.asksToListen(null, wantsListen = false))
    }
}

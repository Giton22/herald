package dev.hermeskotlin.core.cron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutinesTest {

    @Test
    fun aRoutineIsNamedAndReadAsBotModeNamesIt() {
        assertEquals("[bot:scribe] Morning brief", Routines.name("scribe", " Morning brief "))
        assertEquals("scribe", Routines.taggedBot("[bot:Scribe] Morning brief"))
        assertEquals("Morning brief", Routines.title("[bot:scribe]   Morning brief"))
        assertNull(Routines.taggedBot("Morning brief"))
        assertEquals("Morning brief", CronJob(id = "a", name = "[bot:scribe] Morning brief").displayName)
    }

    @Test
    fun aRoutineBelongsToTheBotWhoseStoreHoldsIt() {
        assertTrue(CronJob(id = "a", name = "Anything", profile = "scribe").isRoutineOf("scribe"))
        // Desktop's older routines lived in the launch profile's store, tagged with their bot.
        assertTrue(CronJob(id = "a", name = "[bot:scribe] Old", profile = "default").isRoutineOf("scribe"))
        assertFalse(CronJob(id = "a", name = "[bot:scribe] Copied", profile = "researcher").isRoutineOf("scribe"))
        // Untagged jobs of the launch profile are the default bot's.
        assertTrue(CronJob(id = "a", name = "Briefing", profile = "default").isRoutineOf("default"))
        assertTrue(CronJob(id = "a", name = "Briefing").isRoutineOf("default"))
        // Another bot's older routine sits in the launch store too, but it is that bot's, not the default's.
        assertFalse(CronJob(id = "a", name = "[bot:scribe] Old", profile = "default").isRoutineOf("default"))
        assertTrue(CronJob(id = "a", name = "[bot:default] Mine", profile = "default").isRoutineOf("default"))
    }

    @Test
    fun anErrorWithoutAStatusIsStillSaid() {
        // An older store, or a run that died before writing its status, leaves only the error.
        assertEquals("402 insufficient credits", CronJob(id = "a", lastError = "402 insufficient credits\ntrace").problem)
        assertNull(CronJob(id = "a", lastStatus = "ok", lastError = "an old one").problem)
    }

    @Test
    fun aJobSaysWhatWentWrong() {
        assertNull(CronJob(id = "a", lastStatus = "ok").problem)
        assertNull(CronJob(id = "a", state = "paused", pausedReason = "paused by you").problem)
        assertEquals(
            "The last run's result wasn't delivered: bot-chat delivery to profile 'scribe' timed out",
            CronJob(id = "a", lastStatus = "delivery_failed", lastDeliveryError = "bot-chat delivery to profile 'scribe' timed out").problem,
        )
        assertEquals("The last run failed: 402 insufficient credits", CronJob(id = "a", lastStatus = "error", lastError = "402 insufficient credits\ntrace").problem)
        assertEquals("Couldn't run: something isn't set up", CronJob(id = "a", lastStatus = "blocked_config").problem)
        assertEquals("Stopped after an error", CronJob(id = "a", state = "error").problem)
    }
}

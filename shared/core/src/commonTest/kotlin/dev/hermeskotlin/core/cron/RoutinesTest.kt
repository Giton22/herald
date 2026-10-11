package dev.hermeskotlin.core.cron

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    @Test
    fun aMissedFireIsSaidUntilARunSaysMore() {
        fun stamp(at: String, detail: String) = buildJsonObject { put("at", at); put("detail", detail) }
        val missed = CronJob(id = "a", lastStatus = "ok", lastRunAt = "2026-10-02T09:00:00+02:00", fireError = stamp("2026-10-03T09:00:00+02:00", "runner unreachable"))
        assertTrue(missed.missedLastFire)
        assertEquals("Missed its last scheduled run: runner unreachable", missed.problem)
        // The gateway stamps `str(detail or "")`: a miss without a reason is still a miss.
        assertEquals("Missed its last scheduled run", missed.copy(fireError = stamp("2026-10-03T09:00:00+02:00", "")).problem)
        assertEquals("Missed its last scheduled run: model timeout", missed.copy(fireError = JsonPrimitive("model timeout")).problem)
        assertNull(missed.copy(fireError = JsonPrimitive(true)).problem)

        // A failed run keeps the stamp: whichever is newer is the one to say.
        val failed = missed.copy(lastStatus = "error", lastError = "402 insufficient credits")
        assertEquals("Missed its last scheduled run: runner unreachable", failed.problem)
        assertEquals("The last run failed: 402 insufficient credits", failed.copy(lastRunAt = "2026-10-04T09:00:00+02:00").problem)
        assertFalse(failed.copy(fireError = JsonPrimitive("old")).missedLastFire)
        assertFalse(failed.copy(fireError = buildJsonObject { put("detail", "no time") }).missedLastFire)
        assertTrue(failed.copy(lastRunAt = null).missedLastFire)
        // Python's isoformat() writes microseconds.
        assertTrue(failed.copy(fireError = stamp("2026-10-03T09:00:00.123456+02:00", "x")).missedLastFire)
        assertEquals(
            "Missed its last scheduled run: runner unreachable",
            missed.copy(lastStatus = "delivery_failed", lastDeliveryError = "timed out").problem,
        )
        // A time without an offset doesn't parse.
        assertEquals(null, "2026-10-03T09:00:00".epochSeconds())

        // Paused, done or stopped: not waiting on fires.
        assertNull(missed.copy(state = "paused").problem)
        assertNull(missed.copy(state = "completed").problem)
        assertEquals("Stopped after an error", missed.copy(state = "error", lastStatus = null).problem)
    }
}

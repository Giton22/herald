package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.Checkpoint
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckpointTimeTest {

    private val now = 1_791_540_000_000L // 2026-10-09T10:00:00Z

    @Test
    fun theSameMomentReadsTheSameWhateverOffsetGitWrote() {
        // Shown in the phone's zone, like a message's time, not as the gateway's wall clock.
        val gateway = checkpointTime(Checkpoint("9d752f94aa", "2026-10-09T11:17:03+02:00", ""), use24Hour = true, nowMillis = now)
        val utc = checkpointTime(Checkpoint("9d752f94aa", "2026-10-09T09:17:03Z", ""), use24Hour = true, nowMillis = now)
        assertEquals(utc, gateway)
    }

    @Test
    fun withoutATimeTheHashStandsIn() {
        assertEquals("9d752f94", checkpointTime(Checkpoint("9d752f94aa", "", ""), use24Hour = true, nowMillis = now))
        assertEquals("sometime", checkpointTime(Checkpoint("9d752f94aa", "sometime", ""), use24Hour = true, nowMillis = now))
    }
}

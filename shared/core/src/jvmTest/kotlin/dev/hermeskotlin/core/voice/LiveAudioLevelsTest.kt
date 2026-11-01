package dev.hermeskotlin.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveAudioLevelsTest {
    private var nanos = 1_000_000_000L
    private fun advance(ms: Long) { nanos += ms * 1_000_000 }

    @Test fun currentLevelsAreBoundedAndDoNotRemainVisibleWithoutReports() {
        val levels = LiveAudioLevels { nanos }
        assertEquals(0f, levels.micLevel()); assertFalse(levels.speaking())
        levels.update(.6, .3)
        assertEquals(.6f, levels.micLevel()); assertTrue(levels.speaking())
        advance(600)
        assertEquals(0f, levels.micLevel()); assertFalse(levels.speaking())
        levels.update(3.0, 0.0); assertEquals(1f, levels.micLevel())
        levels.update(-1.0, 0.0); assertEquals(0f, levels.micLevel())
    }

    @Test fun shortPausesDoNotFlickerSpeakingButLongPausesBecomeSilent() {
        val levels = LiveAudioLevels { nanos }
        levels.update(0.0, .1); advance(150); levels.update(0.0, 0.0)
        assertTrue(levels.speaking())
        advance(251); assertFalse(levels.speaking())
    }

    @Test fun invalidLevelsNeverBecomeSoundOrMicActivity() {
        val levels = LiveAudioLevels { nanos }
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            levels.update(value, value)
            assertEquals(0f, levels.micLevel()); assertFalse(levels.speaking())
        }
    }

    @Test fun muteAndRetirementPreventLateStatsFromRestoringCaptureLevels() {
        val levels = LiveAudioLevels { nanos }
        levels.update(.8, .2); levels.mute(true); levels.update(.8, .2)
        assertEquals(0f, levels.micLevel()); assertTrue(levels.speaking())
        levels.mute(false); assertEquals(0f, levels.micLevel())
        levels.update(.4, .2); assertEquals(.4f, levels.micLevel())
        levels.close(); levels.mute(false); levels.update(.9, .9)
        assertEquals(0f, levels.micLevel()); assertFalse(levels.speaking())
    }
}

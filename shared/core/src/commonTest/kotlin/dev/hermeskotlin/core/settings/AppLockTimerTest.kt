package dev.hermeskotlin.core.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTimerTest {

    @Test
    fun aColdStartIsLockedOnlyWithTheLockOn() {
        assertTrue(AppLockTimer().apply { onColdStart(enabled = true) }.locked.value)
        assertFalse(AppLockTimer().apply { onColdStart(enabled = false) }.locked.value)
    }

    @Test
    fun aRecreatedScreenIsNotAColdStart() {
        val lock = AppLockTimer()
        lock.onColdStart(enabled = true)
        lock.onUnlocked()
        lock.onColdStart(enabled = true)
        assertFalse(lock.locked.value)
    }

    @Test
    fun locksAfterAMinuteAway() {
        val lock = AppLockTimer()
        lock.onColdStart(enabled = true)
        lock.onUnlocked()

        lock.onBackground(now = 1_000)
        lock.onForeground(now = 60_999)
        assertFalse(lock.locked.value, "59.999 s away stays open")

        lock.onBackground(now = 100_000)
        lock.onForeground(now = 160_000)
        assertTrue(lock.locked.value, "a full minute away locks")
    }

    @Test
    fun theTimeAwayCountsFromWhenTheAppFirstLeft() {
        val lock = AppLockTimer(timeoutMillis = 10)
        lock.onColdStart(enabled = true)
        lock.onUnlocked()
        lock.onBackground(now = 0)
        lock.onBackground(now = 8)
        lock.onForeground(now = 10)
        assertTrue(lock.locked.value)
    }

    @Test
    fun turningTheLockOnDoesNotLockTheAppInHand() {
        val lock = AppLockTimer()
        lock.onColdStart(enabled = false)
        lock.setEnabled(true)
        assertFalse(lock.locked.value)

        lock.onBackground(now = 0)
        lock.onForeground(now = 60_000)
        assertTrue(lock.locked.value)
    }

    @Test
    fun turningTheLockOffUnlocksAndStopsTheTimer() {
        val lock = AppLockTimer()
        lock.onColdStart(enabled = true)
        lock.setEnabled(false)
        assertFalse(lock.locked.value)

        lock.onBackground(now = 0)
        lock.onForeground(now = 600_000)
        assertFalse(lock.locked.value)
    }

    @Test
    fun comingBackWithoutLeavingDoesNothing() {
        val lock = AppLockTimer()
        lock.onColdStart(enabled = true)
        lock.onUnlocked()
        lock.onForeground(now = 999_999)
        assertFalse(lock.locked.value)
    }
}

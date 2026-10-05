package dev.hermeskotlin.core.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTimerTest {

    /** The app opened in a new process, the way MainActivity's first onCreate reports it. */
    private fun AppLockTimer.coldStart(enabled: Boolean) = apply { onLaunch(enabled, fresh = true) }

    @Test
    fun aColdStartIsLockedOnlyWithTheLockOn() {
        assertTrue(AppLockTimer().coldStart(enabled = true).locked.value)
        assertFalse(AppLockTimer().coldStart(enabled = false).locked.value)
    }

    @Test
    fun aRecreatedScreenIsNotALaunch() {
        val lock = AppLockTimer().coldStart(enabled = true)
        lock.onUnlocked()
        lock.onLaunch(enabled = true, fresh = false)
        assertFalse(lock.locked.value)
    }

    @Test
    fun aScreenRestoredInANewProcessIsLocked() {
        // Android killed the process in the background and brought the screen back from its saved state.
        assertTrue(AppLockTimer().apply { onLaunch(enabled = true, fresh = false) }.locked.value)
    }

    @Test
    fun reopeningAfterClosingLocksEvenThoughTheProcessLived() {
        // Swiped from Recents: a foreground service keeps the process, and the app opens again seconds later.
        val lock = AppLockTimer().coldStart(enabled = true)
        lock.onUnlocked()
        lock.onBackground(now = 0)
        lock.onLaunch(enabled = true, fresh = true)
        lock.onForeground(now = 2_000)
        assertTrue(lock.locked.value)
    }

    @Test
    fun locksAfterAMinuteAway() {
        val lock = AppLockTimer().coldStart(enabled = true)
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
        val lock = AppLockTimer(timeoutMillis = 10).coldStart(enabled = true)
        lock.onUnlocked()
        lock.onBackground(now = 0)
        lock.onBackground(now = 8)
        lock.onForeground(now = 10)
        assertTrue(lock.locked.value)
    }

    @Test
    fun turningTheLockOnDoesNotLockTheAppInHand() {
        val lock = AppLockTimer().coldStart(enabled = false)
        lock.setEnabled(true)
        assertFalse(lock.locked.value)

        lock.onBackground(now = 0)
        lock.onForeground(now = 60_000)
        assertTrue(lock.locked.value)
    }

    @Test
    fun turningTheLockOffUnlocksAndStopsTheTimer() {
        val lock = AppLockTimer().coldStart(enabled = true)
        lock.setEnabled(false)
        assertFalse(lock.locked.value)

        lock.onBackground(now = 0)
        lock.onForeground(now = 600_000)
        assertFalse(lock.locked.value)
    }

    @Test
    fun comingBackWithoutLeavingDoesNothing() {
        val lock = AppLockTimer().coldStart(enabled = true)
        lock.onUnlocked()
        lock.onForeground(now = 999_999)
        assertFalse(lock.locked.value)
    }
}

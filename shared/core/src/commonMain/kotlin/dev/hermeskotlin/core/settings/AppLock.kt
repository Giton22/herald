package dev.hermeskotlin.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When App lock covers the app: on a cold start, and on coming back after [timeoutMillis] or more away.
 * Turning the lock on doesn't lock the app in hand; turning it off unlocks it. Times are any monotonic
 * clock in milliseconds (the host passes `SystemClock.elapsedRealtime()`), so tests can drive it.
 */
class AppLockTimer(private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var enabled = false
    private var started = false
    private var leftAt: Long? = null

    /** The process started: locked from the first frame when the lock is on. */
    fun onColdStart(enabled: Boolean) {
        if (started) return
        started = true
        this.enabled = enabled
        _locked.value = enabled
    }

    /** The lock was switched in Settings (or its stored value was read). */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            _locked.value = false
            leftAt = null
        }
    }

    /** The app left the screen at [now]. */
    fun onBackground(now: Long) {
        if (leftAt == null) leftAt = now
    }

    /** The app is back on screen at [now]; locks when it was away long enough. */
    fun onForeground(now: Long) {
        val left = leftAt ?: return
        leftAt = null
        if (enabled && now - left >= timeoutMillis) _locked.value = true
    }

    /** The user proved it's them. */
    fun onUnlocked() {
        _locked.value = false
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}

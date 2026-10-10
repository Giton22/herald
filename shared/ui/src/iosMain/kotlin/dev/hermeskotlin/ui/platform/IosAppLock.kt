@file:OptIn(ExperimentalForeignApi::class)

package dev.hermeskotlin.ui.platform

import dev.hermeskotlin.core.settings.AppLockTimer
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.systemBackgroundColor
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * App lock's iOS side, as AppLockGate is on Android: follows the setting, locks after a minute away, asks for
 * Face ID, Touch ID or the passcode, and covers the app in the app switcher while the lock is on.
 */
internal class IosAppLock(private val settings: SettingsStore, scope: CoroutineScope) {
    val timer = AppLockTimer()
    private val defaults = NSUserDefaults.standardUserDefaults
    private var enabled = defaults.boolForKey(KEY_APP_LOCK)
    private var asking = false
    private var cover: UIView? = null

    init {
        // The stored setting is read asynchronously; this copy decides the very first frame.
        timer.onLaunch(enabled, fresh = true)
        scope.launch {
            settings.settings.filterNotNull().map { it.appLock }.distinctUntilChanged().collect { on ->
                defaults.setBool(on, KEY_APP_LOCK)
                enabled = on
                timer.setEnabled(on)
            }
        }
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        center.addObserverForName(UIApplicationWillResignActiveNotification, null, main) { _ -> if (enabled) showCover() }
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, main) { _ -> timer.onBackground(now()) }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, main) { _ -> timer.onForeground(now()) }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, main) { _ ->
            hideCover()
            ask()
        }
    }

    /** Shows the system's unlock prompt, unless it's already up or the app isn't locked. */
    fun ask() {
        if (!timer.locked.value || asking) return
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)) {
            // The passcode was removed since: there's nothing left to unlock with, so don't lock the person out.
            timer.onUnlocked()
            return
        }
        asking = true
        context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = "Unlock Herald") { ok, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                asking = false
                // Cancelled or failed: the cover stays, with its Unlock button to ask again.
                if (ok) timer.onUnlocked()
            }
        }
    }

    /** A plain view over the window, so the app switcher's snapshot shows nothing of the chats. */
    private fun showCover() {
        if (cover != null) return
        val window = keyWindow() ?: return
        cover = UIView(frame = window.bounds).apply {
            backgroundColor = UIColor.systemBackgroundColor
            autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            window.addSubview(this)
        }
    }

    private fun hideCover() {
        cover?.removeFromSuperview()
        cover = null
    }

    private fun now(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()

    private companion object {
        const val KEY_APP_LOCK = "app_lock"
    }
}

package dev.hermeskotlin.android.notify

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An activity that shows none of Herald's chats, so it doesn't count as Herald being in sight: the call screen. */
interface OutOfSight

/** Whether any of our activities is on screen; notifications only matter while none is. */
class AppVisibility(application: Application) {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private var started = 0

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity is OutOfSight) return
                started++
                _visible.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity is OutOfSight) return
                started = (started - 1).coerceAtLeast(0)
                _visible.value = started > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

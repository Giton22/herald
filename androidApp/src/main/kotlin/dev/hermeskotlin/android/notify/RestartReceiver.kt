package dev.hermeskotlin.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes the app after a restart of the phone or an update of Herald, so Notifications anywhere picks its
 * ntfy stream up again ([dev.hermeskotlin.android.push.PushListener]); the work happens as the process
 * starts, nothing is left to do here.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

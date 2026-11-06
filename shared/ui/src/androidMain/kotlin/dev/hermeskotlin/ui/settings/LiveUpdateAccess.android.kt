package dev.hermeskotlin.ui.settings

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
actual fun rememberLiveUpdateAccess(): LiveUpdateAccess {
    val context = LocalContext.current
    // Bumped when the user may have changed something in Android's settings: back from there, or a prompt answered.
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }
    return remember(version) { AndroidLiveUpdateAccess(context, ask = { prompt.launch(it) }) }
}

private class AndroidLiveUpdateAccess(
    private val context: Context,
    private val ask: (String) -> Unit,
) : LiveUpdateAccess {

    override val supported: Boolean
        get() = Build.VERSION.SDK_INT >= LIVE_UPDATES_SDK

    override val allowed: Boolean
        get() = supported && notificationsOn() && promotedOn()

    override fun request() {
        val missingPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(POST_NOTIFICATIONS)
        if (missingPermission) {
            // Asked before and refused for good: only Android's settings page can turn it on now.
            val activity = context as? Activity
            val store = context.getSharedPreferences(ASKED_STORE, Context.MODE_PRIVATE)
            val asked = store.getBoolean(ASKED, false)
            if (!asked || activity == null || ActivityCompat.shouldShowRequestPermissionRationale(activity, POST_NOTIFICATIONS)) {
                store.edit { putBoolean(ASKED, true) }
                ask(POST_NOTIFICATIONS)
            } else {
                openNotificationSettings()
            }
            return
        }
        if (!notificationsOn()) {
            openNotificationSettings()
            return
        }
        // Not in the public SDK: asked by its documented name, and Android's own notification page if nothing
        // answers it. Both open in Kyllene's task, so Back comes back here.
        val promoted = Intent(PROMOTED_NOTIFICATIONS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!open(promoted)) openNotificationSettings()
    }

    private fun notificationsOn() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun promotedOn(): Boolean =
        Build.VERSION.SDK_INT >= LIVE_UPDATES_SDK &&
            context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true

    private fun openNotificationSettings() = open(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Whether Android had a page for [intent]. */
    private fun open(intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private companion object {
        /** Live Updates arrived in Android 16. */
        const val LIVE_UPDATES_SDK = 36

        @Suppress("InlinedApi")
        const val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

        /** Android's promoted-notification page, not in the public SDK. */
        const val PROMOTED_NOTIFICATIONS = "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"

        /** Whether the notification prompt was shown, to tell "never asked" from "refused for good". */
        const val ASKED_STORE = "live_update_access"
        const val ASKED = "asked"
    }
}

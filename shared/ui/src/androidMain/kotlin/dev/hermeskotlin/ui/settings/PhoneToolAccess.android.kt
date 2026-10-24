package dev.hermeskotlin.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.hermeskotlin.core.phone.PhoneTool

@Composable
actual fun rememberPhoneToolAccess(): PhoneToolAccess {
    val context = LocalContext.current
    // Bumped when the user may have changed something in Android's settings: back from there, or a prompt answered.
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }
    val healthPrompt = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { version++ }
    // Health Connect only says what it allows asynchronously; null until it has.
    val health by produceState<HealthState?>(null, version) { value = healthState(context) }
    return remember(version, health) {
        AndroidPhoneToolAccess(context, health, ask = { prompt.launch(it) }, askHealth = { healthPrompt.launch(it) })
    }
}

/** Whether the phone has Health Connect, what Herald may read there, and whether reading while closed exists. */
private class HealthState(val status: Int, val granted: Set<String>, val background: Boolean)

private suspend fun healthState(context: Context): HealthState {
    val status = HealthConnectClient.getSdkStatus(context)
    if (status != HealthConnectClient.SDK_AVAILABLE) return HealthState(status, emptySet(), false)
    val client = HealthConnectClient.getOrCreate(context)
    return runCatching {
        HealthState(
            status,
            client.permissionController.getGrantedPermissions(),
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE,
        )
    }.getOrElse { HealthState(status, emptySet(), false) }
}

private class AndroidPhoneToolAccess(
    private val context: Context,
    private val health: HealthState?,
    private val ask: (Array<String>) -> Unit,
    private val askHealth: (Set<String>) -> Unit,
) : PhoneToolAccess {

    override fun missing(tool: PhoneTool): String? = when {
        tool in ASKS && !notificationsOn() -> "Turn on Herald's notifications: changes are asked there."
        tool == PhoneTool.Sound && !policyAccess() -> "Allow Herald to change Do Not Disturb."
        tool == PhoneTool.ScreenTime && !usageAccess() -> "Give Herald Usage access, to see which apps you use."
        tool == PhoneTool.Location && !granted(FINE) && !granted(COARSE) -> "Allow Herald to use this phone's location."
        tool == PhoneTool.Geofences && !granted(FINE) -> "Allow precise location: Android only watches places with it."
        tool == PhoneTool.Geofences && needsBackground() ->
            "Allow location all the time: places are watched while Herald is closed. Choose \"Allow all the time\"."
        tool == PhoneTool.Calendar && (!granted(READ_CALENDAR) || !granted(WRITE_CALENDAR)) -> "Allow Herald to use your calendar."
        tool == PhoneTool.Contacts && !granted(READ_CONTACTS) -> "Allow Herald to read your contacts."
        // A call from the agent rings as an approval does.
        tool == PhoneTool.CallMe -> ringsMissing()
        tool == PhoneTool.Health -> when {
            health == null -> null
            health.status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Update Health Connect, where the data comes from."
            // Before Android 14 it's an app of its own, from the Play Store.
            health.status != HealthConnectClient.SDK_AVAILABLE && Build.VERSION.SDK_INT in Build.VERSION_CODES.P until Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                "Install Health Connect, where the data comes from."
            health.status != HealthConnectClient.SDK_AVAILABLE -> "This phone has no Health Connect."
            health.granted.none { it in HEALTH_READS } -> "Allow Herald to read steps, sleep or heart rate in Health Connect."
            else -> null
        }
        else -> null
    }

    private fun needsBackground() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(BACKGROUND)

    override fun request(tool: PhoneTool) {
        when {
            tool == PhoneTool.CallMe -> requestRings()
            tool in ASKS && !notificationsOn() -> requestNotifications()
            // Both, so the prompt offers precise or approximate: the user picks.
            tool == PhoneTool.Location && !granted(FINE) && !granted(COARSE) -> prompt(FINE, COARSE)
            // Precise first: Android only offers "all the time" once Herald has location while in use.
            tool == PhoneTool.Geofences && !granted(FINE) -> prompt(FINE, COARSE)
            tool == PhoneTool.Geofences && needsBackground() -> prompt(BACKGROUND)
            tool == PhoneTool.Calendar && (!granted(READ_CALENDAR) || !granted(WRITE_CALENDAR)) -> prompt(READ_CALENDAR, WRITE_CALENDAR)
            tool == PhoneTool.Contacts && !granted(READ_CONTACTS) -> prompt(READ_CONTACTS)
            tool == PhoneTool.Health && health != null && health.status != HealthConnectClient.SDK_AVAILABLE &&
                (
                    health.status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ||
                        Build.VERSION.SDK_INT in Build.VERSION_CODES.P until Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ) ->
                open(Intent(Intent.ACTION_VIEW, "market://details?id=$HEALTH_CONNECT&url=healthconnect%3A%2F%2Fonboarding".toUri()))
            // Health Connect's own screen: the user picks which of the three.
            tool == PhoneTool.Health && health?.status == HealthConnectClient.SDK_AVAILABLE -> askHealth(HEALTH_READS)
            tool == PhoneTool.Sound && !policyAccess() -> open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            tool == PhoneTool.ScreenTime && !usageAccess() -> {
                // Herald's own switch where Android has a page for it (10 and later), else the list of apps.
                val list = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                if (!open(Intent(list).setData(Uri.fromParts("package", context.packageName, null)))) open(list)
            }
        }
    }

    override fun optional(tool: PhoneTool): String? = when {
        tool == PhoneTool.Location && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (granted(FINE) || granted(COARSE)) && !granted(BACKGROUND) -> "Also while Herald is closed (optional)"
        // Some of the three allowed: the rest stay one tap away, before reading while closed.
        tool == PhoneTool.Health && health != null && health.granted.any { it in HEALTH_READS } && !health.granted.containsAll(HEALTH_READS) ->
            "Also steps, sleep or heart rate (optional)"
        tool == PhoneTool.Health && health != null && health.background && health.granted.any { it in HEALTH_READS } &&
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in health.granted -> "Also while Herald is closed (optional)"
        else -> null
    }

    override fun requestOptional(tool: PhoneTool) {
        // Android 11 and later answer this one with Herald's location page, where "Allow all the time" is a choice.
        if (tool == PhoneTool.Location && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) prompt(BACKGROUND)
        if (tool == PhoneTool.Health && health != null) {
            val rest = HEALTH_READS - health.granted
            askHealth(rest.ifEmpty { setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) })
        }
    }

    override fun ringsMissing(): String? = when {
        !notificationsOn(CALL_CHANNEL) -> "Turn on Herald's notifications: the call rings there."
        !fullScreen() -> "Allow full-screen notifications, so the call shows over the lock screen."
        else -> null
    }

    override fun requestRings() {
        when {
            !notificationsOn(CALL_CHANNEL) -> requestNotifications()
            // Android 14 and later: a switch of its own, on Herald's page.
            !fullScreen() -> open(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.fromParts("package", context.packageName, null)),
            )
        }
    }

    private fun fullScreen() = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The permission prompt, or Herald's page in Android's settings once the prompt was refused for good. */
    private fun prompt(vararg permissions: String) {
        val first = permissions.first()
        val activity = context as? Activity
        // Kept across restarts: a refused-for-good prompt shows nothing, so the first tap after one would do nothing.
        val store = context.getSharedPreferences(ASKED_STORE, Context.MODE_PRIVATE)
        val asked = store.getStringSet(ASKED, emptySet()).orEmpty()
        if (activity == null || first !in asked || ActivityCompat.shouldShowRequestPermissionRationale(activity, first)) {
            store.edit { putStringSet(ASKED, asked + first) }
            ask(arrayOf(*permissions))
        } else {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)))
        }
    }

    private fun notificationsOn(channel: String = CHANNEL) = NotificationManagerCompat.from(context).let { manager ->
        manager.areNotificationsEnabled() &&
            manager.getNotificationChannelCompat(channel)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    private fun requestNotifications() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val activity = context as? Activity
        val prompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED &&
            // Asked before and refused for good: only the settings page can turn them on now.
            (activity == null || !askedBefore || ActivityCompat.shouldShowRequestPermissionRationale(activity, permission))
        if (prompt) {
            askedBefore = true
            ask(arrayOf(permission))
        } else {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }

    private fun policyAccess() = context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    private fun usageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Whether Android had a page for [intent]. */
    private fun open(intent: Intent): Boolean {
        // In Herald's own task, so Back from the settings page comes back here and not into a Settings app left open.
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private companion object {
        /** Tools that ask before changing the phone, in a notification on this channel (PhoneConfirmations). */
        val ASKS = setOf(PhoneTool.Sound, PhoneTool.Alarms, PhoneTool.Open, PhoneTool.Clipboard, PhoneTool.Geofences, PhoneTool.Calendar)
        const val CHANNEL = "phone_tools"

        /** Where an approval or a question rings (ChatNotifications). */
        const val CALL_CHANNEL = "calls"
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
        const val READ_CALENDAR = Manifest.permission.READ_CALENDAR
        const val WRITE_CALENDAR = Manifest.permission.WRITE_CALENDAR
        const val READ_CONTACTS = Manifest.permission.READ_CONTACTS
        const val HEALTH_CONNECT = "com.google.android.apps.healthdata"
        val HEALTH_READS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
        )

        @SuppressLint("InlinedApi") // Only asked on Android 10 and later.
        const val BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION

        /** Permissions prompted for, to tell "never asked" from "refused for good". */
        const val ASKED_STORE = "phone_tool_access"
        const val ASKED = "asked"

        /** Whether the notification prompt was shown in this process, to tell "never asked" from "refused for good". */
        var askedBefore = false
    }
}

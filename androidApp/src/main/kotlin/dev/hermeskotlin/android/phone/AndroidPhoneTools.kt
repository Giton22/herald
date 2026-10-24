package dev.hermeskotlin.android.phone

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.PhoneToolRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The phone tools on Android: each reads or does what the agent asked, through the platform's own APIs. */
class AndroidPhoneTools(
    private val context: Context,
    confirmations: PhoneConfirmations,
    places: Geofences,
    chats: ChatTargets,
    notifications: ChatNotifications,
) : PhoneToolRunner {

    private val sound = SoundTool(context, confirmations)
    private val alarms = AlarmTool(context, confirmations)
    private val screenTime = ScreenTimeTool(context)
    private val open = OpenTool(context, confirmations)
    private val clipboard = ClipboardTool(confirmations)
    private val location = LocationTool(context)
    private val geofences = GeofenceTool(context, confirmations, location, places, chats)
    private val calendar = CalendarTool(context, confirmations)
    private val contacts = ContactsTool(context)
    private val health = HealthTool(context)
    private val callMe = CallMeTool(notifications, chats)

    override suspend fun run(tool: PhoneTool, args: JsonObject, session: String): JsonElement = withContext(Dispatchers.IO) {
        when (tool) {
            PhoneTool.Battery -> battery()
            PhoneTool.Network -> network()
            PhoneTool.Sound -> sound.run(args)
            PhoneTool.Alarms -> alarms.run(args)
            PhoneTool.ScreenTime -> screenTime.run(args)
            PhoneTool.Open -> open.run(args)
            PhoneTool.Clipboard -> clipboard.run(args)
            PhoneTool.Location -> location.run(args)
            PhoneTool.Geofences -> geofences.run(args, session)
            PhoneTool.Calendar -> calendar.run(args)
            PhoneTool.Contacts -> contacts.run(args)
            PhoneTool.Health -> health.run(args)
            PhoneTool.CallMe -> callMe.run(args, session)
        }
    }

    private fun battery(): JsonObject {
        // A sticky broadcast: registering for it with no receiver hands back the latest state.
        val state = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw PhoneToolException("The phone didn't say how its battery is.")
        val level = state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = state.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        val status = state.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = state.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val manager = context.getSystemService(BatteryManager::class.java)
        return buildJsonObject {
            if (level >= 0) put("percent", level * 100 / scale)
            put(
                "state",
                when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                    else -> "unknown"
                },
            )
            put(
                "plugged_into",
                when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "charger"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    0 -> "nothing"
                    else -> "other"
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager?.computeChargeTimeRemaining()?.takeIf { it > 0 }?.let { put("minutes_until_full", (it / 60_000).toInt()) }
            }
            state.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let {
                put("temperature_c", it / 10.0)
            }
            context.getSystemService(PowerManager::class.java)?.let { put("battery_saver", it.isPowerSaveMode) }
        }
    }

    private fun network(): JsonObject {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: throw PhoneToolException("The phone didn't say how it's connected.")
        val caps = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        return buildJsonObject {
            if (caps == null) {
                put("connection", "offline")
                return@buildJsonObject
            }
            // A VPN usually carries the transport of the link under it (VPN|WIFI). When it names only itself, the
            // link is the one Android would pick: Wi-Fi before ethernet before mobile, a working one first.
            val link = transportOf(caps) ?: run {
                @Suppress("DEPRECATION") // allNetworks: the one way to see the link under a VPN without a callback.
                val links = manager.allNetworks.mapNotNull(manager::getNetworkCapabilities).filter {
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                links.sortedByDescending { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                    .firstNotNullOfOrNull(::transportOf)
            }
            put("connection", link ?: "other")
            put("internet", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            put("vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            put("data_saver", manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        }
    }

    /** The physical link [caps] runs over, in the order Android prefers them; null for a VPN that hides it. */
    private fun transportOf(caps: NetworkCapabilities): String? = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> null
        else -> "other"
    }
}

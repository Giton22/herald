package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.push.PluginUpdate
import dev.hermeskotlin.core.settings.AppSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * An agent tool that runs on this phone. [id] is the tool's name on the gateway (the herald-push plugin's
 * fixed list); [title] and [detail] are what Settings → Phone tools shows by its switch.
 */
enum class PhoneTool(val id: String, val title: String, val detail: String) {
    Battery("phone_battery", "Battery", "The level, whether it's charging, and the time until full."),
    Network("phone_network", "Network", "Wi-Fi, mobile data or offline, whether it's metered, and a VPN."),
    Sound("phone_sound", "Sound", "Do Not Disturb, the ringer and volumes. A change asks you first."),
    Alarms("phone_alarm", "Alarms and timers", "Sets one in your clock app after you allow it."),
    ScreenTime("phone_screen_time", "Screen time", "Which apps you used and for how long, screen-on time and unlocks, up to a week back."),
    Open("phone_open", "Open links, maps and apps", "Opens a link, a place, an app or the share sheet after you allow it."),
    Clipboard("phone_clipboard", "Copy to the clipboard", "Puts text on your clipboard after you allow it. Never reads what's there."),
    Location("phone_location", "Location", "Where the phone is and the nearest address, as precisely as you allow. While Kyllene is open, unless you also allow it while closed."),
    Geofences("phone_geofence", "Watched places", "Arriving at or leaving a place sends its message to the chat it was added from. Adding or removing one asks you first; needs precise location all the time."),
    Calendar("phone_calendar", "Calendar", "Your events in a time range. Adding or moving one asks you first."),
    Contacts("phone_contacts", "Contacts", "Looks people up by name, number or email: only their names, numbers and emails, a few at a time."),
    Health("phone_health", "Health", "Steps, sleep and heart rate from Health Connect, as much of it as you allow there. While Kyllene is open, unless you also allow it while closed."),

    /** Shown under Calls, not with the tools: it rings, as an approval does with [AppSettings.phoneRings]. */
    CallMe(
        "phone_call_me",
        "Hermes can call me",
        "The agent rings you when something can't wait, or when you ask it to call you, from a scheduled job too. " +
            "Answer, and Hermes tells you why on the line. It only ever calls you.",
    ),
    ;

    companion object {
        fun byId(id: String): PhoneTool? = entries.firstOrNull { it.id == id }
    }
}

/** The tools the agent may use now: the ones switched on, while the Phone tools switch itself is on. */
fun AppSettings.enabledPhoneTools(): Set<PhoneTool> =
    if (!phoneTools) emptySet() else phoneToolsOn.mapNotNull(PhoneTool::byId).toSet()

/** An approval or a question rings this phone like a call: its own switch, under the Phone tools switch. */
val AppSettings.ringsForAnswers: Boolean get() = phoneTools && phoneRings

/** Whether [minuteOfDay] (0 to 1439, this phone's clock) falls in quiet hours, when they're on: nothing rings then. */
fun AppSettings.quietNow(minuteOfDay: Int): Boolean = quietHours && inQuietWindow(quietFrom, quietUntil, minuteOfDay)

/**
 * Whether [minute] falls from [from] up to, not including, [until], all minutes into the day. A window whose end is
 * earlier than its start crosses midnight (22:00 to 07:00); one that starts where it ends is empty.
 */
fun inQuietWindow(from: Int, until: Int, minute: Int): Boolean = when {
    from == until -> false
    from < until -> minute in from until until
    else -> minute >= from || minute < until
}

/** "22:00", for [minuteOfDay]. */
fun clockTime(minuteOfDay: Int): String {
    val m = minuteOfDay.mod(MINUTES_A_DAY)
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

const val MINUTES_A_DAY = 24 * 60

/** Why a tool couldn't do what the agent asked, in words the agent passes on to the user. */
class PhoneToolException(message: String) : Exception(message)

/** Does what the agent asked of the phone. The platform side: Android reads the battery, sets the alarm, … */
interface PhoneToolRunner {
    /**
     * Runs [tool] with the agent's [args] for the chat [session] (empty when the gateway didn't say) and returns
     * the result the agent reads. Throws [PhoneToolException] for anything the agent should be told.
     */
    suspend fun run(tool: PhoneTool, args: JsonObject, session: String): JsonElement
}

/** One time the agent used a phone tool: shown on the Phone tools page, so nothing happens unseen. */
data class PhoneToolUse(val tool: PhoneTool, val atMillis: Long, val error: String?)

/** Where phone tools stand with the gateway, as a line under the Phone tools switch. */
data class PhoneToolsStatus(
    val state: State = State.Off,
    val detail: String? = null,
    /** A newer gateway plugin, waiting for the user to update to it. */
    val update: PluginUpdate? = null,
) {
    enum class State { Off, Offline, Working, NotInstalled, Outdated, On, Failed }
}

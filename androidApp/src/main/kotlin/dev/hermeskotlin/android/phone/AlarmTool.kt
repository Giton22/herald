package dev.hermeskotlin.android.phone

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.hidden
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar
import java.util.Locale

/**
 * `phone_alarm`: an alarm or a timer in the phone's clock app, when enabled. The clock app keeps it,
 * so the user sees and cancels it there like any other.
 */
internal class AlarmTool(private val context: Context, private val confirmations: PhoneConfirmations) {

    suspend fun run(args: JsonObject): JsonObject = when (args.string("kind")) {
        "alarm" -> alarm(args)
        "timer" -> timer(args)
        else -> throw PhoneToolException("kind must be alarm or timer.")
    }

    private suspend fun alarm(args: JsonObject): JsonObject {
        val hour = args.int("hour") ?: throw PhoneToolException("An alarm needs hour and minute.")
        val minute = args.int("minute") ?: throw PhoneToolException("An alarm needs hour and minute.")
        if (hour !in 0..23 || minute !in 0..59) throw PhoneToolException("hour is 0 to 23 and minute 0 to 59.")
        val days = days(args)
        val label = label(args)
        val time = "%d:%02d".format(Locale.ROOT, hour, minute)
        val what = buildString {
            append("Set an alarm for $time")
            if (days.isNotEmpty()) append(", ${repeats(days)}")
            if (label != null) append(": \"$label\"")
            append('.')
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map(DAYS::getValue)))
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(intent, what)
        return buildJsonObject {
            put("sent_to_clock_app", "alarm")
            put("note", HANDED_OVER)
            put("time", time)
            if (days.isNotEmpty()) put("days", JsonArray(days.map(::JsonPrimitive)))
            if (label != null) put("label", label)
        }
    }

    private suspend fun timer(args: JsonObject): JsonObject {
        val seconds = args.int("seconds") ?: throw PhoneToolException("A timer needs seconds.")
        if (seconds !in 1..86_400) throw PhoneToolException("seconds is 1 to 86400.")
        val label = label(args)
        val what = "Start a ${length(seconds)} timer" + (label?.let { ": \"$it\"" } ?: "") + "."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(intent, what)
        return buildJsonObject {
            put("sent_to_clock_app", "timer")
            put("note", HANDED_OVER)
            put("seconds", seconds)
            if (label != null) put("label", label)
        }
    }

    /**
     * Asks, then hands [intent] to the clock app from the user's tap. In its own task: a clock app that opens its
     * editor anyway mustn't open it in the hidden one Allow runs in, where the user couldn't get back to it.
     */
    private suspend fun start(intent: Intent, what: String) {
        if (intent.resolveActivity(context.packageManager) == null) {
            throw PhoneToolException("The phone has no clock app that takes alarms and timers.")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        confirmations.confirm(PhoneTool.Alarms, what, needsActivity = true) { activity -> activity.startActivity(intent) }
    }

    private fun days(args: JsonObject): List<String> {
        val raw = args["days"]?.takeIf { it != JsonNull } ?: return emptyList()
        val list = raw as? JsonArray ?: throw PhoneToolException("days is a list like [\"mon\", \"fri\"].")
        val days = list.map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: throw PhoneToolException("days is a list of day names like [\"mon\", \"fri\"].")
        }
        days.firstOrNull { it !in DAYS }?.let { throw PhoneToolException("$it isn't a day: use mon, tue, wed, thu, fri, sat or sun.") }
        return DAYS.keys.filter { it in days }
    }

    /** "every weekday", "every day", or the days by name, for the question. */
    private fun repeats(days: List<String>): String = when (days) {
        DAYS.keys.toList() -> "every day"
        DAYS.keys.take(5) -> "every weekday"
        DAYS.keys.drop(5) -> "every weekend"
        else -> "every " + days.joinToString(", ") { DAY_WORDS.getValue(it) }
    }

    private fun label(args: JsonObject): String? =
        args.string("label")?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.codePointCount(0, it.length) > 80) throw PhoneToolException("label is at most 80 characters.")
            // It sits in quotes in the question: it mustn't close them, break the line or hide anything.
            if (hidden(it, newlines = false)) throw PhoneToolException("label has hidden characters, line breaks or odd spaces in it.")
            if ('"' in it) throw PhoneToolException("label can't have \" in it.")
        }

    private fun length(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it h" },
            m.takeIf { it > 0 }?.let { "$it min" },
            s.takeIf { it > 0 }?.let { "$it s" },
        ).joinToString(" ")
    }

    private companion object {
        /** Android says nothing back from a clock app, so "sent" is all that's known. */
        const val HANDED_OVER = "The clock app got it. If the clock app opened instead, the user finishes it there."

        val DAYS = linkedMapOf(
            "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
            "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY,
        )
        val DAY_WORDS = mapOf(
            "mon" to "Monday", "tue" to "Tuesday", "wed" to "Wednesday", "thu" to "Thursday",
            "fri" to "Friday", "sat" to "Saturday", "sun" to "Sunday",
        )
    }
}

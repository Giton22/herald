package dev.hermeskotlin.android.phone

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.forQuestion
import dev.hermeskotlin.core.phone.hidden
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * `phone_calendar`: the phone's own calendars (Android's calendar provider, whichever accounts sync to it). Listing
 * reads; adding and moving ask first, showing what changes. Only single events are moved: a change to one of a
 * repeating series would need the user's say on which ones, and that's for the calendar app.
 */
internal class CalendarTool(private val context: Context, private val confirmations: PhoneConfirmations) {

    suspend fun run(args: JsonObject): JsonObject = when (args.string("action")) {
        "list" -> list(args)
        "add" -> add(args)
        "move" -> move(args)
        else -> throw PhoneToolException("action is list, add or move.")
    }

    private fun list(args: JsonObject): JsonObject {
        need(Manifest.permission.READ_CALENDAR)
        val zone = ZoneId.systemDefault()
        val from = args.string("from")?.let { time(it, "from") } ?: LocalDate.now(zone).atStartOfDay()
        val to = args.string("to")?.let { time(it, "to") } ?: from.plusDays(1)
        if (!to.isAfter(from)) throw PhoneToolException("to must be after from.")
        if (Duration.between(from, to) > Duration.ofDays(MAX_DAYS)) throw PhoneToolException("A list covers at most $MAX_DAYS days.")
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.atZone(zone).toInstant().toEpochMilli())
            ContentUris.appendId(it, to.atZone(zone).toInstant().toEpochMilli())
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        // Cancelled events are gone for the user too; declined ones stay, marked, as the calendar app shows them.
        val where = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "${CalendarContract.Instances.STATUS} IS NOT ${CalendarContract.Events.STATUS_CANCELED}"
        var total = 0
        var listed = 0
        // What the listed events take as JSON: the whole answer must fit what the gateway takes back at once.
        var size = 0
        var full = false
        return buildJsonObject {
            put("from", from.format(LOCAL))
            put("to", to.format(LOCAL))
            putJsonArray("events") {
                context.contentResolver.query(
                    uri, columns, where, null, "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        total++
                        if (full || listed >= MAX_EVENTS) continue
                        val allDay = c.getInt(4) == 1
                        val event = buildJsonObject {
                            put("event_id", c.getLong(0).toString())
                            put("title", (c.text(1) ?: "(no title)").short())
                            put("start", shown(c.getLong(2), allDay))
                            // An all-day event's last day, as people count it; the provider's end is the day after.
                            put("end", shown(c.getLong(3), allDay, last = true))
                            if (allDay) put("all_day", true)
                            c.text(5)?.let { put("location", it.short()) }
                            c.text(6)?.let { put("calendar", it.short()) }
                            if (c.text(7) != null) put("repeats", true)
                            if (c.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) put("declined", true)
                        }
                        // The gateway's JSON adds a space after each : and , that this one doesn't.
                        size += event.toString().length + 2 * event.size + 2
                        // The first ones in order, never a gap: past the limit, the rest are only counted.
                        full = size > MAX_LIST_CHARS
                        if (full) continue
                        add(event)
                        listed++
                    }
                }
            }
            if (listed < total) put("note", "Only the first $listed of $total events; ask for a shorter range for the rest.")
        }
    }

    private suspend fun add(args: JsonObject): JsonObject {
        need(Manifest.permission.READ_CALENDAR)
        need(Manifest.permission.WRITE_CALENDAR)
        val title = text(args, "title", MAX_TITLE) ?: throw PhoneToolException("title is needed.")
        val location = text(args, "location", MAX_TITLE)
        val notes = text(args, "notes", MAX_NOTES, newlines = true)
        val allDay = args.boolean("all_day") ?: false
        val startText = args.string("start") ?: throw PhoneToolException("start is needed.")
        val zone = ZoneId.systemDefault()
        val values = ContentValues()
        val start: LocalDateTime
        val end: LocalDateTime
        if (allDay) {
            // Whole days: end is the last day, as people say it. The provider wants UTC days, its end the day after.
            val first = time(startText, "start").toLocalDate()
            val last = args.string("end")?.let { time(it, "end").toLocalDate() } ?: first
            if (last < first) throw PhoneToolException("For an all-day event, end is its last day: the same day as start or later.")
            start = first.atStartOfDay()
            end = last.plusDays(1).atStartOfDay()
            values.put(CalendarContract.Events.ALL_DAY, 1)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            values.put(CalendarContract.Events.DTSTART, first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            values.put(CalendarContract.Events.DTEND, last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        } else {
            // Through the zone and back: a time the clocks skip shows as the one the event really gets.
            start = time(startText, "start").atZone(zone).toLocalDateTime()
            end = (args.string("end")?.let { time(it, "end") } ?: start.plusHours(1)).atZone(zone).toLocalDateTime()
            if (!end.isAfter(start)) throw PhoneToolException("end must be after start.")
            values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            values.put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            values.put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
        }
        val calendar = writableCalendar() ?: throw PhoneToolException("The phone has no calendar Kyllene can add to.")
        values.put(CalendarContract.Events.CALENDAR_ID, calendar.id)
        values.put(CalendarContract.Events.TITLE, title)
        location?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        notes?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
        // When, where and which calendar first, the agent's own text after: a title can't push them out of sight.
        val what = buildString {
            append("${span(start, end, allDay)}, in ${forQuestion(calendar.shown)}")
            location?.let { append(", at ${forQuestion(it)}") }
            append(".\nTitle: ${forQuestion(title)}")
            notes?.let { append("\nNotes: ${forQuestion(it)}") }
        }
        var id = 0L
        confirmations.confirm(what, TITLE_ADD) {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: throw PhoneToolException("The calendar didn't take the event.")
            id = ContentUris.parseId(uri)
        }
        return buildJsonObject {
            put("added", true)
            put("event_id", id.toString())
            put("calendar", calendar.name)
            if (allDay) {
                put("start", start.toLocalDate().toString())
                put("end", end.toLocalDate().minusDays(1).toString())
                put("all_day", true)
            } else {
                put("start", start.format(LOCAL))
                put("end", end.format(LOCAL))
            }
        }
    }

    private suspend fun move(args: JsonObject): JsonObject {
        need(Manifest.permission.READ_CALENDAR)
        need(Manifest.permission.WRITE_CALENDAR)
        val id = args.string("event_id")?.toLongOrNull() ?: throw PhoneToolException("event_id is needed: take it from action=list.")
        val startText = args.string("start") ?: throw PhoneToolException("start is needed: when the event moves to.")
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val event = event(uri) ?: throw PhoneToolException("No event has that event_id.")
        if (event.repeats) throw PhoneToolException("That event repeats. Moving one of a series is for the calendar app; ask the user to do it there.")
        if (event.access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) throw PhoneToolException("That event's calendar is read-only.")
        if (event.allDay) throw PhoneToolException("Moving all-day events isn't supported; ask the user to do it in the calendar app.")
        // An invitation sits in the user's own calendar, but only its organizer can move it: sync would undo the change.
        if (!event.organizing && !event.guestsCanModify) {
            throw PhoneToolException("The user was invited to that event; only its organizer can move it.")
        }
        val length = event.length ?: throw PhoneToolException("That event's length isn't known: give end too.")
        val zone = ZoneId.systemDefault()
        val start = time(startText, "start").atZone(zone)
        // The same real length, also across a change of the clocks.
        val end = args.string("end")?.let { time(it, "end").atZone(zone) } ?: start.plus(length).withZoneSameInstant(zone)
        if (!end.isAfter(start)) throw PhoneToolException("end must be after start.")
        val was = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.start), zone)
        // Only the times change: the event keeps its own time zone, which its guests see it in.
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
            if (event.zone == null) put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            if (event.hasDuration) putNull(CalendarContract.Events.DURATION)
        }
        val guests = guests(id)
        val what = buildString {
            append("From ${at(was)} to ${span(start.toLocalDateTime(), end.toLocalDateTime(), false)}, in ${forQuestion(event.calendar)}.")
            if (guests > 0) append(" ${if (guests == 1) "1 guest" else "$guests guests"} may be told.")
            append("\nTitle: ${forQuestion(event.title)}")
        }
        confirmations.confirm(what, TITLE_MOVE) {
            if (context.contentResolver.update(uri, values, null, null) != 1) throw PhoneToolException("The calendar didn't take the change.")
        }
        return buildJsonObject {
            put("moved", true)
            put("event_id", id.toString())
            put("start", start.toLocalDateTime().format(LOCAL))
            put("end", end.toLocalDateTime().format(LOCAL))
        }
    }

    private class Event(
        val title: String,
        val start: Long,
        /** How long it lasts, from its end or its RFC 5545 duration; null when neither reads. */
        val length: Duration?,
        val hasDuration: Boolean,
        val allDay: Boolean,
        val repeats: Boolean,
        val access: Int,
        val zone: String?,
        val organizing: Boolean,
        val guestsCanModify: Boolean,
        val calendar: String,
    )

    private fun event(uri: Uri): Event? {
        val columns = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ORGANIZER,
            CalendarContract.Events.OWNER_ACCOUNT,
            CalendarContract.Events.GUESTS_CAN_MODIFY,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )
        return context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val start = c.getLong(1)
            val duration = c.text(3)
            val length = if (!c.isNull(2) && c.getLong(2) > start) Duration.ofMillis(c.getLong(2) - start) else duration?.let(::rfcDuration)
            val organizer = c.text(8)
            Event(
                title = c.text(0) ?: "(no title)",
                start = start,
                length = length,
                hasDuration = duration != null,
                allDay = c.getInt(4) == 1,
                repeats = c.text(5) != null,
                access = c.getInt(6),
                zone = c.text(7),
                // No organizer: a plain event of the user's own.
                organizing = organizer == null || organizer.equals(c.text(9), ignoreCase = true),
                guestsCanModify = c.getInt(10) == 1,
                calendar = c.text(11) ?: "your calendar",
            )
        }
    }

    /** Guests other than the organizer, who the calendar's sync may tell of a change. */
    private fun guests(eventId: Long): Int {
        val where = "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
            "${CalendarContract.Attendees.ATTENDEE_RELATIONSHIP} != ${CalendarContract.Attendees.RELATIONSHIP_ORGANIZER}"
        return context.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI, arrayOf(CalendarContract.Attendees._ID), where, arrayOf(eventId.toString()), null,
        )?.use { it.count } ?: 0
    }

    private class Calendar(val id: Long, val name: String, val shown: String)

    /** The calendar new events go in: the primary one the user can write to, else the first such. */
    private fun writableCalendar(): Calendar? {
        val columns = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        val where = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND " +
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
        class Found(val id: Long, val name: String, val primary: Boolean, val account: String?)
        val found = mutableListOf<Found>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns, where, null, "${CalendarContract.Calendars._ID} ASC")?.use { c ->
            while (c.moveToNext()) found += Found(c.getLong(0), c.text(1) ?: "your calendar", c.getInt(2) == 1, c.text(3))
        }
        val pick = found.firstOrNull { it.primary } ?: found.firstOrNull() ?: return null
        // Two calendars of one name: the account tells the user which.
        val shown = if (found.count { it.name == pick.name } > 1 && pick.account != null) "${pick.name} (${pick.account})" else pick.name
        return Calendar(pick.id, pick.name, shown)
    }

    /** "PT1H", or RFC 5545's "P3600S" and "P1D" as some sync adapters store them. */
    private fun rfcDuration(text: String): Duration? {
        val match = RFC_DURATION.matchEntire(text.trim()) ?: return runCatching { Duration.parse(text.trim()) }.getOrNull()
        val (weeks, days, hours, minutes, seconds) = match.destructured
        return Duration.ofDays((weeks.toLongOrNull() ?: 0) * 7 + (days.toLongOrNull() ?: 0))
            .plusHours(hours.toLongOrNull() ?: 0).plusMinutes(minutes.toLongOrNull() ?: 0).plusSeconds(seconds.toLongOrNull() ?: 0)
            .takeIf { !it.isZero }
    }

    private fun need(permission: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            throw PhoneToolException("Kyllene isn't allowed to use the calendar. The user can allow it in Kyllene: Settings → Phone tools.")
        }
    }

    private fun text(args: JsonObject, name: String, max: Int, newlines: Boolean = false): String? {
        val value = args.string(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > max) throw PhoneToolException("$name is at most $max characters.")
        if (hidden(value.replace("\r\n", "\n"), newlines)) throw PhoneToolException("$name has hidden characters or odd spaces in it.")
        return value
    }

    /** "2026-10-14T09:30", or "2026-10-14" for the start of that day; local time. */
    private fun time(text: String, name: String): LocalDateTime = try {
        if (text.length <= DATE_LENGTH) LocalDate.parse(text).atStartOfDay() else LocalDateTime.parse(text)
    } catch (e: DateTimeParseException) {
        throw PhoneToolException("$name is a local date and time like 2026-10-14T09:30, or a date like 2026-10-14.")
    }

    private fun shown(millis: Long, allDay: Boolean, last: Boolean = false): String = if (allDay) {
        val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        (if (last) day.minusDays(1) else day).toString()
    } else {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(LOCAL)
    }

    /** "Wed 14 Oct, 9:30–10:30" for the question; the year too when it isn't this one. */
    private fun span(start: LocalDateTime, end: LocalDateTime, allDay: Boolean): String = when {
        allDay && end.toLocalDate().minusDays(1) <= start.toLocalDate() -> "${day(start)}, all day"
        allDay -> "${day(start)} to ${day(end.minusDays(1))}, all day"
        start.toLocalDate() == end.toLocalDate() -> "${day(start)}, ${start.format(HOUR)}–${end.format(HOUR)}"
        else -> "${at(start)} to ${at(end)}"
    }

    private fun day(time: LocalDateTime): String {
        val pattern = if (time.year == LocalDate.now().year) "EEE d MMM" else "EEE d MMM yyyy"
        return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    private fun at(time: LocalDateTime) = "${day(time)}, ${time.format(HOUR)}"

    /** At most [MAX_LISTED] characters, never cutting an emoji in half. */
    private fun String.short(): String {
        if (length <= MAX_LISTED) return this
        val cut = if (this[MAX_LISTED - 2].isHighSurrogate()) MAX_LISTED - 2 else MAX_LISTED - 1
        return take(cut) + "…"
    }

    private fun Cursor.text(i: Int): String? = if (isNull(i)) null else getString(i)?.takeIf { it.isNotBlank() }

    private companion object {
        const val MAX_DAYS = 31L
        const val MAX_EVENTS = 100

        /** Characters of a title, place or calendar in a list. */
        const val MAX_LISTED = 150

        /** The listed events' JSON: under the gateway's 60 000 characters, with room for the rest of the answer. */
        const val MAX_LIST_CHARS = 55_000
        const val MAX_TITLE = 200
        // Short enough that the question shows all of it.
        const val MAX_NOTES = 500
        const val DATE_LENGTH = 10
        const val TITLE_ADD = "The agent wants to add to your calendar"
        const val TITLE_MOVE = "The agent wants to move an event"
        val RFC_DURATION = Regex("""^\+?P(?:(\d+)W)?(?:(\d+)D)?(?:T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")
        val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT)
        // In the user's language, read each time: it can change while Kyllene runs.
        val HOUR: DateTimeFormatter get() = DateTimeFormatter.ofPattern("H:mm", Locale.getDefault())
    }
}

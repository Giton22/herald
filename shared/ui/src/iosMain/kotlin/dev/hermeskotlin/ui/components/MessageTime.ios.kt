package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierISO8601
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitWeekday
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

actual fun localMoment(epochMillis: Long): LocalMoment {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    val zone = NSTimeZone.localTimeZone
    // Gregorian fields in the device's zone, whatever calendar the device shows.
    val calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierISO8601).apply { timeZone = zone }
    val parts = calendar.components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitWeekday or NSCalendarUnitHour or NSCalendarUnitMinute,
        fromDate = date,
    )
    val localSeconds = epochMillis / 1000 + zone.secondsFromGMTForDate(date)
    return LocalMoment(
        epochDay = localSeconds.floorDiv(SECONDS_PER_DAY),
        year = parts.year.toInt(),
        month = parts.month.toInt(),
        day = parts.day.toInt(),
        // Foundation counts 1 (Sunday) to 7 (Saturday).
        dayOfWeek = ((parts.weekday.toInt() + 5) % 7) + 1,
        hour = parts.hour.toInt(),
        minute = parts.minute.toInt(),
    )
}

@Composable
actual fun uses24HourClock(): Boolean = remember {
    // The locale's short time pattern has an "a" (AM/PM) when the device is on a 12-hour clock.
    val pattern = NSDateFormatter.dateFormatFromTemplate("j", options = 0u, locale = NSLocale.currentLocale).orEmpty()
    !pattern.contains('a')
}

private const val SECONDS_PER_DAY = 86_400L


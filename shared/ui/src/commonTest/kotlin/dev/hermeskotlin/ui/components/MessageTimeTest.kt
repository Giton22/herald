package dev.hermeskotlin.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageTimeTest {

    // Monday 6 October 2025, 14:05.
    private val now = LocalMoment(epochDay = 20_367, year = 2025, month = 10, day = 6, dayOfWeek = 1, hour = 14, minute = 5)

    private fun daysBefore(days: Int, hour: Int, minute: Int, month: Int = 10, day: Int = 6 - days, year: Int = 2025, dayOfWeek: Int = 1) =
        LocalMoment(now.epochDay - days, year, month, day, dayOfWeek, hour, minute)

    @Test
    fun todayIsJustTheClockTime() {
        assertEquals("09:07", messageTime(daysBefore(0, 9, 7), now, use24Hour = true))
        assertEquals("9:07 AM", messageTime(daysBefore(0, 9, 7), now, use24Hour = false))
        assertEquals("12:30 PM", messageTime(daysBefore(0, 12, 30), now, use24Hour = false))
        assertEquals("12:00 AM", messageTime(daysBefore(0, 0, 0), now, use24Hour = false))
    }

    @Test
    fun thisWeekNamesTheDay() {
        assertEquals("Yesterday 23:59", messageTime(daysBefore(1, 23, 59, dayOfWeek = 7), now, use24Hour = true))
        assertEquals("Thu 8:00 PM", messageTime(daysBefore(4, 20, 0, dayOfWeek = 4), now, use24Hour = false))
    }

    @Test
    fun olderTimesGiveTheDate() {
        assertEquals("Sep 3, 10:15", messageTime(daysBefore(33, 10, 15, month = 9, day = 3), now, use24Hour = true))
        assertEquals("Dec 24, 2024", messageTime(daysBefore(286, 18, 0, month = 12, day = 24, year = 2024), now, use24Hour = true))
    }

    @Test
    fun aMessageWithoutATimeShowsNone() {
        assertEquals("", messageTime(null, use24Hour = true))
        assertEquals("", messageTime(0.0, use24Hour = true))
    }

    @Test
    fun epochSecondsAreReadInTheDeviceZone() {
        // 14:05 UTC is nowhere near midnight in any zone, so 3 seconds earlier is today: a bare clock time.
        val nowMillis = 1_759_759_500_000L
        val label = messageTime(nowMillis / 1000.0 - 3, use24Hour = true, nowMillis = nowMillis)
        assertTrue(Regex("""^\d{2}:\d{2}$""").matches(label), label)
    }
}

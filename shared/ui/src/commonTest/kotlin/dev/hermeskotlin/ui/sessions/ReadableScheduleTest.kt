package dev.hermeskotlin.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadableScheduleTest {

    @Test
    fun commonCronShapesReadAsWords() {
        assertEquals("Daily at 09:00", readableSchedule("0 9 * * *"))
        assertEquals("Weekdays at 18:30", readableSchedule("30 18 * * 1-5"))
        assertEquals("Mondays at 07:05", readableSchedule("5 7 * * 1"))
        assertEquals("Sundays at 07:05", readableSchedule("5 7 * * 7"))
    }

    @Test
    fun anythingElsePassesThrough() {
        assertEquals("every 30m", readableSchedule("every 30m"))
        assertEquals("*/15 * * * *", readableSchedule("*/15 * * * *"))
        assertEquals("0 9 1 * *", readableSchedule("0 9 1 * *"))
    }
}

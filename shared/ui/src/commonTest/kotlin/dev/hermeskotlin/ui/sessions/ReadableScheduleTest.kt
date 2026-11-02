package dev.hermeskotlin.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadableScheduleTest {

    @Test
    fun commonCronShapesAreRead() {
        assertEquals(ReadableSchedule.Daily("09:00"), readableSchedule("0 9 * * *"))
        assertEquals(ReadableSchedule.Weekdays("18:30"), readableSchedule("30 18 * * 1-5"))
        assertEquals(ReadableSchedule.Weekly(1, "07:05"), readableSchedule("5 7 * * 1"))
        // Both 0 and 7 are Sunday.
        assertEquals(ReadableSchedule.Weekly(0, "07:05"), readableSchedule("5 7 * * 7"))
        assertEquals(ReadableSchedule.Weekly(0, "07:05"), readableSchedule("5 7 * * 0"))
    }

    @Test
    fun anythingElsePassesThrough() {
        assertEquals(ReadableSchedule.AsIs("every 30m"), readableSchedule("every 30m"))
        assertEquals(ReadableSchedule.AsIs("*/15 * * * *"), readableSchedule("*/15 * * * *"))
        assertEquals(ReadableSchedule.AsIs("0 9 1 * *"), readableSchedule("0 9 1 * *"))
        assertEquals(ReadableSchedule.AsIs("0 9 * * -1"), readableSchedule("0 9 * * -1"))
    }
}

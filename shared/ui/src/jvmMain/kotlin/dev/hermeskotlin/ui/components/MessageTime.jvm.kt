package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId

actual fun localMoment(epochMillis: Long): LocalMoment {
    val at = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return LocalMoment(
        epochDay = at.toLocalDate().toEpochDay(),
        year = at.year,
        month = at.monthValue,
        day = at.dayOfMonth,
        dayOfWeek = at.dayOfWeek.value,
        hour = at.hour,
        minute = at.minute,
    )
}

// The JVM has no clock setting of its own: the locale's short time format says which clock it uses.
@Composable
actual fun uses24HourClock(): Boolean = remember {
    (DateFormat.getTimeInstance(DateFormat.SHORT) as? SimpleDateFormat)?.toPattern()?.contains('H') ?: true
}

package dev.hermeskotlin.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.ZoneId

actual fun localMoment(epochMillis: Long): LocalMoment {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return LocalMoment(
        epochDay = time.toLocalDate().toEpochDay(),
        year = time.year,
        month = time.monthValue,
        day = time.dayOfMonth,
        dayOfWeek = time.dayOfWeek.value,
        hour = time.hour,
        minute = time.minute,
    )
}

@Composable
actual fun uses24HourClock(): Boolean {
    val context = LocalContext.current
    return remember(context) { DateFormat.is24HourFormat(context) }
}

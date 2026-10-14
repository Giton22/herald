package dev.hermeskotlin.core.threads

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal actual fun localStamp(epochMillis: Long, withSeconds: Boolean): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (withSeconds) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm"))

package dev.hermeskotlin.core.threads

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

internal actual fun localStamp(epochMillis: Long, withSeconds: Boolean): String {
    val formatter = NSDateFormatter()
    // A fixed pattern, not the user's date style: POSIX keeps the digits and the 24-hour clock as written.
    formatter.locale = NSLocale(localeIdentifier = "en_US_POSIX")
    formatter.timeZone = NSTimeZone.localTimeZone
    formatter.dateFormat = if (withSeconds) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm"
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}

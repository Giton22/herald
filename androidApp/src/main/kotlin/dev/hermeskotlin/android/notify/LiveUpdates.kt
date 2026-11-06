package dev.hermeskotlin.android.notify

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** Android's Live Update support, read from the system rather than cached. */
internal object LiveUpdates {
    /**
     * Whether Android can show a Live Update at all, and the user still allows it for Kyllene. Reads the system
     * each time.
     */
    fun supported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true
    }
}

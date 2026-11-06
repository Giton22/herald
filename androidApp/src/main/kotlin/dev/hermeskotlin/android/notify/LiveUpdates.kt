package dev.hermeskotlin.android.notify

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** Whether Android can show a Live Update at all, and the user still allows it for Kyllene. */
internal object LiveUpdates {
    fun supported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true
    }
}

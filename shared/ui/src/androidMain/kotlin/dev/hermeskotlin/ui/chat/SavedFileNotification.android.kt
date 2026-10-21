package dev.hermeskotlin.ui.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** A completed save stays in the shade, with the saved URI rather than the gateway URL. */
internal fun notifySavedFile(context: Context, uri: Uri, name: String, mime: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (!manager.areNotificationsEnabled()) return
    // Created once at high importance for the heads-up banner; Android keeps any importance the user picks later.
    val channelId = "download-completion"
    manager.createNotificationChannel(
        NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pop-up alerts for files and pictures saved to this phone"
        },
    )
    val open = Intent(context, OpenSavedFileActivity::class.java).setDataAndType(uri, mime)
        .putExtra(Intent.EXTRA_TITLE, name)
    val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(name)
        .setContentText("Download complete · Tap to open")
        .setContentIntent(tap)
        .addAction(android.R.drawable.ic_menu_view, "Open", tap)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
    // A URI per save keeps two files, even with the same name, independently tappable.
    manager.notify(uri.toString(), 0, notification)
}

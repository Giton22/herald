package dev.hermeskotlin.ui.chat

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** A completed save stays in the shade, with the saved URI rather than the gateway URL. */
internal fun notifySavedFile(context: Context, uri: Uri, name: String, mime: String, folder: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (!manager.areNotificationsEnabled()) return
    // Android won't raise an existing channel's importance. Migrate the old app default,
    // but keep a channel whose importance the user has explicitly chosen.
    val legacy = manager.getNotificationChannel("downloads")
    val keepLegacy = legacy != null && (
        legacy.importance != NotificationManager.IMPORTANCE_DEFAULT ||
            (Build.VERSION.SDK_INT >= 29 && legacy.hasUserSetImportance())
        )
    val channelId = if (keepLegacy) "downloads" else "download-completion"
    manager.createNotificationChannel(
        NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pop-up alerts for files and pictures saved to this phone"
        },
    )
    val open = Intent(context, OpenSavedFileActivity::class.java).setDataAndType(uri, mime)
    val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(name)
        .setContentText("Download complete · Tap to open")
        .setStyle(NotificationCompat.BigTextStyle().bigText("Saved to $folder · Tap to open"))
        .setContentIntent(tap)
        .addAction(android.R.drawable.ic_menu_view, "Open", tap)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
    // A URI per save keeps two files, even with the same name, independently tappable.
    manager.notify(uri.toString(), 0, notification)
}

/** Opens a saved file after a notification tap, including when Herald has been closed. */
class OpenSavedFileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val uri = intent.data ?: return
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, intent.type ?: "application/octet-stream")
                .apply { clipData = ClipData.newRawUri("Saved file", uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(view)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No app can open this file. It's saved in Herald's folder.", Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            } catch (_: SecurityException) {
                Toast.makeText(this, "Couldn't open the file. It may have been moved or deleted.", Toast.LENGTH_LONG).show()
            }
        } finally {
            finish()
        }
    }
}

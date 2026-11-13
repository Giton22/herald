package dev.hermeskotlin.android.notify

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.voice.ReadAloudKeepAlive
import org.koin.android.ext.android.inject

/**
 * Keeps a reply read aloud going with the screen off or another app in front, the way music does: a media-playback
 * foreground service. Its notification's Stop stops the reading.
 */
class ReadAloudService : Service() {

    private val notifications: ChatNotifications by inject()
    private val keeper: ReadAloudKeeper by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            keeper.stop()
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ReadAloudService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        // Into the foreground first, always: a service started this way and stopped before it is a crash.
        try {
            ServiceCompat.startForeground(
                this,
                ChatNotifications.READ_ALOUD_ID,
                notifications.readAloud(stop),
                // Types are Android 10+; media playback is 10+ too.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            // Android refuses a foreground service started from the background.
            Log.w("ReadAloudService", "Couldn't keep Read aloud up", e)
            keeper.lost()
            stopSelf()
            return START_NOT_STICKY
        }
        // The reading ended while it was starting: it was left to stop here.
        if (!keeper.started()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keeper.stopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val ACTION_STOP = "dev.hermeskotlin.readaloud.STOP"
    }
}

/**
 * [ReadAloudKeepAlive] on Android: holds [ReadAloudService] up for the reply being read. All on the main thread, as
 * the service's callbacks and the voice controller are.
 */
class ReadAloudKeeper(private val context: Context) : ReadAloudKeepAlive {

    private var onStop: (() -> Unit)? = null
    private var onLost: (() -> Unit)? = null
    /** A reading holds it. */
    private var wanted = false
    /** The service is in the foreground, so it may be stopped. */
    private var foreground = false

    override fun hold(onStop: () -> Unit, onLost: () -> Unit): Boolean = try {
        this.onStop = onStop
        this.onLost = onLost
        wanted = true
        ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
        true
    } catch (e: Exception) {
        // Refused, e.g. from the background; the reading then stops when the app goes away.
        Log.w("ReadAloudService", "Couldn't keep Read aloud up", e)
        forget()
        false
    }

    override fun release() {
        forget()
        // One still starting stops itself once it's in the foreground (started).
        if (foreground) context.stopService(Intent(context, ReadAloudService::class.java))
    }

    /** The service is in the foreground; false when the reading ended meanwhile, so it should stop. */
    internal fun started(): Boolean {
        foreground = wanted
        return wanted
    }

    internal fun stopped() {
        foreground = false
    }

    /** The service couldn't go into the foreground after all. */
    internal fun lost() {
        val lost = onLost
        forget()
        lost?.invoke()
    }

    /** The notification's Stop. */
    fun stop() {
        val stop = onStop
        if (stop == null) release() else stop()
    }

    private fun forget() {
        wanted = false
        onStop = null
        onLost = null
    }
}

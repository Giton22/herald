package dev.hermeskotlin.android.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.voice.ReadAloudControls
import dev.hermeskotlin.core.voice.ReadAloudKeepAlive
import org.koin.android.ext.android.inject

/**
 * Keeps a reply read aloud going with the screen off or another app in front, the way music does: a media-playback
 * foreground service with a media session, so the lock screen and the notification shade show Pause, Resume and Stop.
 */
class ReadAloudService : Service() {

    private val notifications: ChatNotifications by inject()
    private val keeper: ReadAloudKeeper by inject()
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        keeper.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                keeper.stop()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                keeper.toggle()
                return START_NOT_STICKY
            }
        }
        val mediaSession = session ?: MediaSession(this, "ReadAloud").also {
            it.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = keeper.resume()

                override fun onPause() = keeper.pause()

                override fun onStop() = keeper.stop()
            })
            it.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, getString(R.string.notification_read_aloud_text))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                    .build(),
            )
            session = it
        }
        mediaSession.setPlaybackState(state(keeper.paused))
        mediaSession.isActive = true
        // Into the foreground first, always: a service started this way and stopped before it is a crash.
        try {
            ServiceCompat.startForeground(
                this,
                ChatNotifications.READ_ALOUD_ID,
                notification(mediaSession, keeper.paused),
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

    /** The reading was paused or went on: the session and the notification say so. */
    internal fun render(paused: Boolean) {
        val mediaSession = session ?: return
        mediaSession.setPlaybackState(state(paused))
        getSystemService(NotificationManager::class.java).notify(ChatNotifications.READ_ALOUD_ID, notification(mediaSession, paused))
    }

    private fun notification(mediaSession: MediaSession, paused: Boolean) = notifications.readAloud(
        mediaSession.sessionToken,
        paused,
        toggle = action(ACTION_TOGGLE),
        stop = action(ACTION_STOP),
    )

    private fun action(action: String) = PendingIntent.getService(
        this, 0, Intent(this, ReadAloudService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun state(paused: Boolean) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
        .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .build()

    override fun onDestroy() {
        session?.release()
        session = null
        keeper.service = null
        keeper.stopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val ACTION_STOP = "dev.hermeskotlin.readaloud.STOP"
        const val ACTION_TOGGLE = "dev.hermeskotlin.readaloud.TOGGLE"
    }
}

/**
 * [ReadAloudKeepAlive] on Android: holds [ReadAloudService] up for the reply being read. All on the main thread, as
 * the service's callbacks and the voice controller are.
 */
class ReadAloudKeeper(private val context: Context) : ReadAloudKeepAlive {

    private var controls: ReadAloudControls? = null
    private var onLost: (() -> Unit)? = null
    /** A reading holds it. */
    private var wanted = false
    /** The service is in the foreground, so it may be stopped. */
    private var foreground = false
    /** The reading is paused, as the controls show it. */
    internal var paused = false
        private set
    /** The service while it runs, to show [paused] on. */
    internal var service: ReadAloudService? = null

    override fun hold(controls: ReadAloudControls, onLost: () -> Unit): Boolean = try {
        this.controls = controls
        this.onLost = onLost
        paused = false
        wanted = true
        ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
        true
    } catch (e: Exception) {
        // Refused, e.g. from the background; the reading then stops when the app goes away.
        Log.w("ReadAloudService", "Couldn't keep Read aloud up", e)
        forget()
        false
    }

    override fun paused(paused: Boolean) {
        if (!wanted || this.paused == paused) return
        this.paused = paused
        service?.render(paused)
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

    /** The notification's Pause or Resume. */
    internal fun toggle() = if (paused) resume() else pause()

    internal fun pause() = act { it.pause() }

    internal fun resume() = act { it.resume() }

    /** The notification's Stop. */
    internal fun stop() = act { it.stop() }

    /** A control pressed with nothing holding the reading (the app was restarted): the service has no use. */
    private fun act(what: (ReadAloudControls) -> Unit) {
        val held = controls
        if (held == null) release() else what(held)
    }

    private fun forget() {
        wanted = false
        paused = false
        controls = null
        onLost = null
    }
}

package dev.hermeskotlin.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.os.HandlerCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import dev.hermeskotlin.wearlink.WatchRing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hermes calling for an answer on the watch: rings like an incoming call, its full screen [RingActivity]. It stops
 * when it's answered or declined here, or when the phone says it's over (answered there, or rung out).
 */
class WatchRings(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    private val main = Handler(Looper.getMainLooper())

    /** The rings stopped lately, which never come back. */
    private val ended = LinkedHashSet<String>()

    private val _ringing = MutableStateFlow<Map<String, WatchRing>>(emptyMap())

    /** The calls ringing now, by request id: the ring screen closes once its own is gone. */
    val ringing: StateFlow<Map<String, WatchRing>> = _ringing.asStateFlow()

    init {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Calls for an answer")
                .setDescription("Hermes calls when it needs an approval or an answer, or something can't wait, if the phone rings for it.")
                .setVibrationEnabled(true)
                .setVibrationPattern(VIBRATION)
                .build(),
        )
    }

    val canRing: Boolean
        get() = manager.areNotificationsEnabled() && (
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    // canRing checks the permission. The ring screen, opened from no activity, needs a task: its own, as the
    // manifest has it, which leaves recents with the call alone.
    @SuppressLint("MissingPermission", "WearRecents")
    fun ring(ring: WatchRing): Boolean {
        if (ring.requestId in _ringing.value) return true
        if (!canRing) return false
        val caller = Person.Builder().setName(ring.title).setImportant(true).build()
        val answer = PendingIntent.getActivity(
            context,
            ("answer" + ring.requestId).hashCode(),
            RingActivity.intent(context, ring, answer = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            context,
            ("decline" + ring.requestId).hashCode(),
            Intent(context, RingDeclineReceiver::class.java).putExtra(RingActivity.EXTRA_REQUEST_ID, ring.requestId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val screen = PendingIntent.getActivity(
            context,
            ("ring" + ring.requestId).hashCode(),
            RingActivity.intent(context, ring, answer = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .setContentText(ringReason(ring.approval, ring.agent))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setOngoing(true)
            .setAutoCancel(false)
            // The phone ends it; should its word never come, it goes by itself.
            .setTimeoutAfter(MAX_RING_MS)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        _ringing.update { it + (ring.requestId to ring) }
        manager.notify(ring.requestId, NOTIFICATION_ID, notification)
        // Rung out here too, should the phone's word that it's over never come: the ring screen goes with it.
        HandlerCompat.postDelayed(main, { stop(ring.requestId) }, ring.requestId, MAX_RING_MS)
        // Wear's system UI only buzzes for a full-screen intent, so the ring screen is opened here as well.
        try {
            context.startActivity(RingActivity.intent(context, ring, answer = false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            // Not allowed from the background here: the notification still rings.
        }
        return true
    }

    /**
     * [ring] from its notification's screen, ringing still: Herald may have been restarted since it began, and only
     * the notification is left of it. False once it's over.
     */
    fun restore(ring: WatchRing): Boolean {
        if (ring.requestId in _ringing.value) return true
        // Stopped in this run: its notification may still be listed for a moment after it was cancelled.
        if (synchronized(ended) { ring.requestId in ended }) return false
        val on = manager.activeNotifications.any { it.tag == ring.requestId && it.id == NOTIFICATION_ID }
        if (on) _ringing.update { it + (ring.requestId to ring) }
        return on
    }

    /** Over: answered, declined, or ended on the phone. The ring, if it was still on. */
    fun stop(requestId: String): WatchRing? {
        synchronized(ended) {
            ended += requestId
            if (ended.size > MAX_ENDED) ended.remove(ended.first())
        }
        main.removeCallbacksAndMessages(requestId)
        manager.cancel(requestId, NOTIFICATION_ID)
        var ring: WatchRing? = null
        _ringing.update {
            ring = it[requestId]
            it - requestId
        }
        return ring
    }

    private companion object {
        const val CHANNEL = "calls"
        const val NOTIFICATION_ID = 7
        const val MAX_RING_MS = 60_000L
        const val MAX_ENDED = 32
        val VIBRATION = longArrayOf(0, 700, 500, 700, 500, 700)
    }
}

/** Decline from the ring's notification: it stops, and the question waits on the phone as a notification. */
class RingDeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(RingActivity.EXTRA_REQUEST_ID) ?: return
        HeraldWatch.of(context).link.declineRing(requestId)
    }
}

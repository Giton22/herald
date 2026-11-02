package dev.hermeskotlin.android.pebble

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.answersOnPebble
import dev.hermeskotlin.core.wear.WatchAnswering
import dev.hermeskotlin.core.wear.ringAnswering
import dev.hermeskotlin.wearlink.PebbleDict
import dev.hermeskotlin.wearlink.PebbleIncoming
import dev.hermeskotlin.wearlink.PebbleLink
import dev.hermeskotlin.wearlink.RingEnd
import dev.hermeskotlin.wearlink.WatchRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rings Kyllene on the Pebbles along with the phone when Hermes calls for an answer, whether or not the gateway talks
 * live: Kyllene is opened there for it. Answer on the watch shows the approval or question there while answering on
 * the Pebble is on (Settings → Connected devices); else the watch only stops ringing and says to answer on the phone,
 * which rings on, and so it does for a call from the agent. Only Pebbles Kyllene heard from ring: Kyllene is installed
 * there.
 */
class PebbleRinger(
    context: Context,
    private val outbox: PebbleOutbox,
    private val requests: PebbleRequests,
    private val notifications: ChatNotifications,
    private val settings: SettingsStore,
) : ChatNotifications.AlsoRings {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("pebble_ringer", Context.MODE_PRIVATE)

    /**
     * A ring going on: the [call], the [ring] the watches are sent and how it's answered there, the Pebbles still
     * ringing, and those of them that haven't said so yet.
     */
    private class Rung(
        val call: ChatNotifications.Ringing,
        val ring: PebbleDict,
        val answering: WatchAnswering,
        val watches: MutableSet<String>,
        val waiting: MutableSet<String>,
    )

    // Everything below is only touched on the main thread.

    /** The Pebbles Kyllene heard from. */
    private var heard: Set<String> = prefs.getStringSet(HEARD, null).orEmpty().toSet()

    private val rung = mutableMapOf<String, Rung>()

    /** The rings whose phone call shows on the Pebble, since none said in time that it rings. */
    private val phoneCallShown = mutableSetOf<String>()

    private val current: AppSettings get() = settings.settings.value ?: AppSettings()

    /** Kyllene on Pebble [node] sent something: it can ring there. */
    fun heardFrom(node: String) = onMain {
        if (node in heard) return@onMain
        heard = (heard + node).toList().takeLast(MAX_WATCHES).toSet()
        prefs.edit { putStringSet(HEARD, heard) }
    }

    // Called on the main thread, as the phone's call is posted.
    override fun ring(call: ChatNotifications.Ringing): Boolean {
        val to = heard
        if (!current.pebble || !current.watchRings || to.isEmpty()) return false
        val request = call.request
        val answering = ringAnswering(request, current.answersOnPebble)
        val watchRing = WatchRing(
            call.id,
            call.storedSessionId.orEmpty(),
            call.title?.takeIf { it.isNotBlank() } ?: "Hermes",
            approval = request is InputRequest.Approval,
            agent = call.agent != null,
        )
        val ring = PebbleLink.ring(watchRing, answering.ordinal)
        val id = call.id
        rung[id] = Rung(call, ring, answering, to.toMutableSet(), to.toMutableSet())
        for (node in to) {
            // Open already, it rings now; else once Kyllene opens there ([opened]).
            outbox.watch(node).message(ring)
            scope.launch { outbox.launch(node) }
        }
        scope.launch {
            // No Pebble said it rings (out of reach, Kyllene gone from it): the phone's call shows there instead.
            delay(RING_ON_MS)
            val now = rung[id] ?: return@launch
            if (now.waiting.containsAll(now.watches) && phoneCallShown.add(id)) notifications.showOnWatch(id, show = true)
        }
        return true
    }

    /** Kyllene opened on [node]: the rings it hasn't said it rings for go to it. */
    fun opened(node: String) {
        rung.values.filter { node in it.waiting }.forEach { outbox.watch(node).message(it.ring) }
    }

    /** Pebble [node] rings for [on]; should that come late, the phone's call shown there meanwhile goes. */
    fun ringsOn(node: String, on: PebbleIncoming.RingOn) {
        val now = rung[on.requestId] ?: return
        now.waiting -= node
        if (phoneCallShown.remove(on.requestId)) notifications.showOnWatch(on.requestId, show = false)
    }

    /**
     * Picked up or declined on Pebble [node]: the phone and every other watch stop ringing, and a pick-up shows the
     * request there. One the watch may not answer (the setting went off meanwhile) only stops ringing there.
     */
    fun endedOnWatch(node: String, end: PebbleIncoming.RingEnd) {
        val now = rung[end.requestId] ?: return
        now.watches -= node
        now.waiting -= node
        val request = now.call.request
        if (end.answered && (request == null || now.answering == WatchAnswering.OnPhone || !current.answersOnPebble)) {
            outbox.watch(node).message(PebbleLink.ringEnd(RingEnd(end.requestId)))
            return
        }
        phoneCallShown -= end.requestId
        if (end.answered && request != null) {
            notifications.answeredOnWatch(end.requestId)
            requests.showFromRing(node, request, now.call.storedSessionId, now.call.title)
        } else {
            notifications.declinedOnWatch(end.requestId)
        }
    }

    override fun stop(requestId: String) = onMain {
        phoneCallShown -= requestId
        val now = rung.remove(requestId) ?: return@onMain
        now.watches.forEach { outbox.watch(it).message(PebbleLink.ringEnd(RingEnd(requestId))) }
    }

    override fun gone(requestId: String) = onMain { requests.gone(requestId) }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val HEARD = "heard"
        const val MAX_WATCHES = 4

        /** How long a Pebble has to say it rings: opening Kyllene there for it takes a few seconds. */
        const val RING_ON_MS = 12_000L
    }
}

package dev.hermeskotlin.android.wear

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.wearlink.RingEnd
import dev.hermeskotlin.wearlink.RingOn
import dev.hermeskotlin.wearlink.WatchRing
import dev.hermeskotlin.wearlink.WearCapabilities
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * Rings Herald on the watch along with the phone, when Hermes calls for an answer: Answer there starts a live call
 * from the watch in that chat, which asks it aloud. Only when the gateway talks live, since the watch has no chat
 * to open instead; else the phone's own call shows on the watch as any notification does.
 */
class WatchRinger(
    context: Context,
    private val relay: WatchRelay,
    private val settings: SettingsStore,
    private val outbox: WatchOutbox,
    private val notifications: ChatNotifications,
    private val scope: CoroutineScope,
) : ChatNotifications.AlsoRings {

    private val capabilities = Wearable.getCapabilityClient(context)
    private val prefs = context.getSharedPreferences("watch_ringer", Context.MODE_PRIVATE)

    /** The watches advertising Herald. Its capability can take minutes to reach the phone, or not come at all. */
    @Volatile
    private var advertised: Set<String> = emptySet()

    /** The watches Herald heard from: whatever the capabilities say, Herald runs there. */
    @Volatile
    private var heard: Set<String> = prefs.getStringSet(HEARD, null).orEmpty().toSet()

    private val watches: Set<String> get() = advertised + heard

    /** The watches each request rang, by request id. */
    private val rung = ConcurrentHashMap<String, Set<String>>()

    /** The requests a watch said it rings for. */
    private val ringingOn: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The requests whose phone call shows on the watch, since none said in time that it rings. */
    private val phoneCallShown: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun start() {
        notifications.alsoRings = this
        capabilities.addListener({ info -> found(info.nodes.mapTo(HashSet()) { it.id }) }, WearCapabilities.WATCH)
        scope.launch {
            val nodes = try {
                capabilities.getCapability(WearCapabilities.WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptySet()
            }
            found(nodes.mapTo(HashSet()) { it.id })
        }
        if (heard.isNotEmpty()) relay.checkLiveVoice()
    }

    private fun found(nodes: Set<String>) {
        advertised = nodes
        // Known ahead, since a ring decides at once whether the watch can take it.
        if (nodes.isNotEmpty()) relay.checkLiveVoice()
    }

    /** Herald on watch [node] sent something: it can ring there. */
    @Synchronized
    fun heardFrom(node: String) {
        if (node in heard) return
        heard = (heard + node).toList().takeLast(MAX_WATCHES).toSet()
        prefs.edit { putStringSet(HEARD, heard) }
        relay.checkLiveVoice()
    }

    override fun ring(call: ChatNotifications.Ringing): Boolean {
        val chat = call.storedSessionId ?: return false
        val to = watches
        if (!(settings.settings.value ?: AppSettings()).watchRings || to.isEmpty()) return false
        val live = relay.talksLive
        // Fresh for the next one, should the gateway have changed.
        relay.checkLiveVoice()
        if (!live) return false
        val id = call.id
        rung[id] = to
        val ring = WatchRing(
            id,
            chat,
            call.title?.takeIf { it.isNotBlank() } ?: "Hermes",
            approval = call.request is InputRequest.Approval,
            agent = call.agent != null,
        )
        scope.launch {
            to.forEach { send(it, WearPaths.RING, WearJson.encode(ring)) }
            // No watch said it rings (no Herald there any more, its notifications off, on a call): the phone's call
            // shows there instead.
            delay(RING_ON_MS)
            if (rung.containsKey(id) && id !in ringingOn && phoneCallShown.add(id)) {
                // Said just now, in between: it rings there after all.
                if (id in ringingOn) phoneCallShown -= id else notifications.showOnWatch(id, show = true)
            }
        }
        return true
    }

    /** Herald on a watch rings for [on]; should that come late, the phone's call shown there meanwhile goes. */
    fun ringsOn(on: RingOn) {
        if (!rung.containsKey(on.requestId) || !ringingOn.add(on.requestId)) return
        if (phoneCallShown.remove(on.requestId)) notifications.showOnWatch(on.requestId, show = false)
    }

    override fun stop(requestId: String) {
        ringingOn -= requestId
        phoneCallShown -= requestId
        val to = rung.remove(requestId) ?: return
        scope.launch { to.forEach { send(it, WearPaths.RING_END, WearJson.encode(RingEnd(requestId))) } }
    }

    /** Picked up or declined on watch [node]: any other watch stops ringing, and so does the phone. */
    fun endedOnWatch(node: String, end: RingEnd) {
        ringingOn -= end.requestId
        phoneCallShown -= end.requestId
        val others = rung.remove(end.requestId).orEmpty() - node
        scope.launch { others.forEach { send(it, WearPaths.RING_END, WearJson.encode(RingEnd(end.requestId))) } }
        if (end.answered) notifications.answeredOnWatch(end.requestId) else notifications.declinedOnWatch(end.requestId)
    }

    private suspend fun send(node: String, path: String, data: ByteArray) {
        try {
            outbox.send(node, path, data)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Out of reach: it rings on the phone alone.
        }
    }

    private companion object {
        const val HEARD = "heard"
        const val MAX_WATCHES = 4

        /** How long a watch has to say it rings: a message each way takes well under a second when it's in reach. */
        const val RING_ON_MS = 4_000L
    }
}

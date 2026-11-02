package dev.hermeskotlin.android.wear

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.hermeskotlin.android.notify.ChatNotifier
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.WatchCalls
import dev.hermeskotlin.ui.voice.watchAccent
import dev.hermeskotlin.wearlink.CallEnd
import dev.hermeskotlin.wearlink.CallEvents
import dev.hermeskotlin.wearlink.ChatListRequest
import dev.hermeskotlin.wearlink.CallStart
import dev.hermeskotlin.wearlink.RecordedAsk
import dev.hermeskotlin.wearlink.PhoneStatusRequest
import dev.hermeskotlin.wearlink.RingEnd
import dev.hermeskotlin.wearlink.RingOn
import dev.hermeskotlin.wearlink.WatchAsk
import dev.hermeskotlin.wearlink.WatchStop
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.ByteArrayOutputStream

/**
 * Where the watch's messages land on the phone, even with Herald closed: each goes to [WatchRelay], which answers
 * over the app's gateway connection. Android wakes this service for messages on [WearPaths.PREFIX] only (the
 * manifest's filter), and only from Herald's own watch app, which shares its package name and signing key.
 */
class WearBridgeService : WearableListenerService() {

    private val settings: SettingsStore by inject()
    private val relay: WatchRelay by inject()
    private val calls: WatchCalls by inject()
    private val scope: CoroutineScope by inject()
    private val notifier: ChatNotifier by inject()
    private val ringer: WatchRinger by inject()

    override fun onMessageReceived(event: MessageEvent) {
        val node = event.sourceNodeId
        ringer.heardFrom(node)
        when (event.path) {
            WearPaths.ASK, WearPaths.BOT_ASK -> WearJson.decode<WatchAsk>(event.data)?.let {
                if (event.path == WearPaths.BOT_ASK && it.botProfile.isNullOrBlank()) return
                relay.ask(node, it)
                keepUp()
            }
            WearPaths.STOP -> WearJson.decode<WatchStop>(event.data)?.let { relay.stop(it.askId) }
            WearPaths.DEFAULT_CALL -> WearJson.decode<String>(event.data)?.let { relay.defaultCall(node, it); keepUp() }
            WearPaths.CALL_START, WearPaths.BOT_CALL_START, WearPaths.DEFAULT_CALL_START -> WearJson.decode<CallStart>(event.data)?.let {
                if (event.path == WearPaths.BOT_CALL_START && it.botProfile.isNullOrBlank()) return
                if (event.path == WearPaths.DEFAULT_CALL_START && it.defaultRequestId.isNullOrBlank()) return
                calls.start(node, it)
                keepUp()
            }
            WearPaths.CALL_EVENTS -> WearJson.decode<CallEvents>(event.data)?.let { calls.events(node, it) }
            WearPaths.CALL_END -> WearJson.decode<CallEnd>(event.data)?.let(calls::end)
            WearPaths.RING_END -> WearJson.decode<RingEnd>(event.data)?.let { ringer.endedOnWatch(node, it) }
            WearPaths.RING_ON -> WearJson.decode<RingOn>(event.data)?.let(ringer::ringsOn)
            WearPaths.CHATS -> { relay.chats(node, WearJson.decode<ChatListRequest>(event.data)?.requestId); keepUp() }
            WearPaths.STATUS -> {
                val request = WearJson.decode<PhoneStatusRequest>(event.data)
                scope.launch {
                    val accent = watchAccent(settings.settings.filterNotNull().first().accent)
                    relay.status(node, request?.botProfile, request?.requestId, request?.scope, accent.updateFor(request?.accentRevision))
                }
            }
        }
    }

    /**
     * Keeps the gateway connection up for the turn the watch just asked for. Started from here, while the message
     * is being delivered: once this returns Android may refuse it, and the turn could stall in the background.
     */
    private fun keepUp() {
        runBlocking(Dispatchers.Main.immediate) { notifier.startServiceNow() }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!WearPaths.isRecording(channel.path)) return
        val channels = Wearable.getChannelClient(this)
        // The ask is read off the channel after this returns, so the connection is kept up for it now,
        // busy at once so nothing lets the service go while it's read.
        relay.expect()
        keepUp()
        scope.launch {
            val bytes = try {
                val input = channels.getInputStream(channel).await()
                withContext(Dispatchers.IO) {
                    input.use { stream ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            // A minute of 16 kHz speech is under 2 MB; anything far past that isn't a recording.
                            if (out.size() > MAX_RECORDING) return@withContext null
                        }
                        out.toByteArray()
                    }
                }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { channels.close(channel) }
            }
            val recorded = bytes?.let(RecordedAsk::read)
            if (recorded == null || WearPaths.isBotRecording(channel.path) && recorded.first.botProfile.isNullOrBlank()) {
                // Unreadable: no longer busy, so the service started for it stops.
                relay.expectedNothing()
                return@launch
            }
            relay.ask(channel.nodeId, recorded.first, recorded.second)
        }
    }

    private companion object {
        const val BUFFER = 16 * 1024
        const val MAX_RECORDING = 8 * 1024 * 1024
    }
}

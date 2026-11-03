package dev.hermeskotlin.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.hermeskotlin.wearlink.WearPaths
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Where Herald on the phone answers, with the app open or not: each message and voice clip goes to [WatchLink]. */
class WatchListenerService : WearableListenerService() {

    private val app get() = HeraldWatch.of(this)

    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach { app.callDisplay.receive(it.dataItem) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        app.link.onMessage(event.path, event.data, event.sourceNodeId)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WearPaths.HISTORY && WearPaths.parseSpeech(channel.path) == null) return
        val channels = Wearable.getChannelClient(this)
        app.scope.launch {
            val bytes = try {
                val input = channels.getInputStream(channel).await()
                withContext(Dispatchers.IO) { input.use { if (channel.path == WearPaths.HISTORY) run {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count = it.read(buffer)
                    while (count >= 0 && output.size() + count <= 4_000_000) {
                        output.write(buffer, 0, count)
                        count = it.read(buffer)
                    }
                    if (count < 0) output.toByteArray() else null
                } else it.readBytes() } }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { channels.close(channel) }
            }
            bytes?.let {
                if (channel.path == WearPaths.HISTORY) app.link.onMessage(channel.path, it, channel.nodeId)
                else app.link.onSpeech(channel.path, it, channel.nodeId)
            }
        }
    }
}

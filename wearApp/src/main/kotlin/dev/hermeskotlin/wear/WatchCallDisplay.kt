package dev.hermeskotlin.wear

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.util.Base64
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dev.hermeskotlin.wearlink.CallDisplay
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.content.edit
import com.google.android.gms.wearable.CapabilityClient
import dev.hermeskotlin.wearlink.WearCapabilities
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicLong

/** Display-only cache, coherent across restarts; the phone still resolves every call. */
internal class WatchCallDisplay(private val context: Context, private val scope: CoroutineScope) {
    private data class Cached(val display: CallDisplay, val image: String? = null)
    private val prefs = context.getSharedPreferences("herald_call_display", Context.MODE_PRIVATE)
    private val serial = Any()
    private val generation = AtomicLong()
    @Volatile private var phone = prefs.getString("phone", null)
    @Volatile private var cached = prefs.getString("snapshot", null)?.let {
        decode(it)?.takeIf { it.display.valid && (!it.display.hasPicture || imageValid(it.image)) }
    }
    val title: String get() = cached?.display?.title ?: "Hermes"

    fun icon(): Icon? = cached?.image?.let { encoded ->
        runCatching { val bytes = Base64.decode(encoded, Base64.NO_WRAP); Icon.createWithData(bytes, 0, bytes.size) }.getOrNull()
    }

    fun bindPhone(node: String) {
        synchronized(serial) {
            if (phone == node) return
            phone = node
            generation.incrementAndGet()
            cached = null
            prefs.edit { putString("phone", node); remove("snapshot") }
        }
        refresh()
        updateComplication()
    }

    fun refresh() {
        val expected = generation.get()
        scope.launch {
            try {
                val nodes = Wearable.getCapabilityClient(context).getCapability(WearCapabilities.PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
                val connected = if (nodes.isEmpty()) Wearable.getNodeClient(context).connectedNodes.await() else emptyList()
                val current = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                    ?: connected.firstOrNull { it.isNearby } ?: connected.firstOrNull()
                if (current != null && current.id != phone) { bindPhone(current.id); return@launch }
                if (phone == null) return@launch
                val items = Wearable.getDataClient(context).dataItems.await()
                try {
                    if (expected == generation.get()) items.filter { it.uri.path == WearPaths.CALL_DISPLAY && it.uri.host == phone }
                        .firstOrNull()?.let(::receive)
                } finally { items.release() }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* A later refresh retries. */ }
        }
    }

    fun receive(item: DataItem) {
        if (item.uri.path != WearPaths.CALL_DISPLAY || (phone == null || item.uri.host != phone)) return
        val map = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull() ?: return
        val display = map.getString("display")?.let { WearJson.decode<CallDisplay>(it.encodeToByteArray()) }?.takeIf { it.valid } ?: return
        val asset = map.getAsset("image")
        val source = item.uri.host
        val version = synchronized(serial) {
            if (source != phone) return
            val next = generation.incrementAndGet()
            if (cached?.display == display) return
            next
        }
        scope.launch(Dispatchers.IO) {
            try {
                synchronized(serial) {
                    if (source == phone && version == generation.get() && cached?.display?.targetKey != display.targetKey) {
                        val fallback = Cached(display.copy(revision = "0".repeat(64), hasPicture = false))
                        val editor = prefs.edit()
                        editor.putString("snapshot", encode(fallback))
                        if (editor.commit()) { cached = fallback; updateComplication() }
                    }
                }
                val bytes = if (!display.hasPicture) null else {
                    if (asset == null) return@launch
                    val result = Wearable.getDataClient(context).getFdForAsset(asset).await()
                    try {
                        result.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (out.size() <= CallDisplay.MAX_IMAGE_BYTES) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                            }
                            out.toByteArray()
                        }
                    } finally { result.release() }
                }
                val image = bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                if (display.hasPicture && !imageValid(image)) return@launch
                val next = Cached(display, image)
                synchronized(serial) {
                    if (source != phone || generation.get() != version || next == cached) return@synchronized
                    // Commit the small metadata/image snapshot before exposing its revision to renderers.
                    val editor = prefs.edit()
                    editor.putString("snapshot", encode(next))
                    if (editor.commit()) {
                        cached = next
                        updateComplication()
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Retain the last coherent snapshot. */ }
        }
    }

    private fun encode(value: Cached): String = WearJson.encode(buildJsonObject {
        put("display", WearJson.json.encodeToJsonElement(value.display))
        value.image?.let { put("image", it) }
    }).decodeToString()

    private fun decode(value: String): Cached? = runCatching {
        val json = WearJson.decode<JsonObject>(value.encodeToByteArray()) ?: return@runCatching null
        val display = WearJson.decode<CallDisplay>(json["display"].toString().encodeToByteArray()) ?: return@runCatching null
        Cached(display, json["image"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()

    private fun updateComplication() {
        ComplicationDataSourceUpdateRequester.create(context,
            android.content.ComponentName(context, AskComplicationService::class.java)).requestUpdateAll()
    }

    private fun imageValid(encoded: String?): Boolean = runCatching {
        if (encoded == null || encoded.length > CallDisplay.MAX_IMAGE_BYTES * 2) return@runCatching false
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size > CallDisplay.MAX_IMAGE_BYTES) return@runCatching false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.outWidth in 1..216 && options.outHeight in 1..216
    }.getOrDefault(false)
}

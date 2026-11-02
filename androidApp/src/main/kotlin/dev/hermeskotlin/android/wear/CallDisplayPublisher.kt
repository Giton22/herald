package dev.hermeskotlin.android.wear

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.hermeskotlin.android.notify.CallShortcut
import dev.hermeskotlin.core.settings.CallTarget
import dev.hermeskotlin.wearlink.CallDisplay
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** One phone-owned snapshot; Data Layer retains it until the paired watch reconnects. */
internal class CallDisplayPublisher(private val context: Context) {
    private var published: String? = null

    suspend fun publish(gateway: String?, target: CallTarget?, picture: ByteArray?,
        pictureKnown: Boolean = picture != null || target?.profile == null) {
        val bitmap = target?.profile?.let { CallShortcut.pictureBitmap(context, picture) }
        // JPEG, the artwork being opaque: a photograph as lossless PNG would often be over the watch's limit.
        val bytes = bitmap?.let {
            try {
                JPEG_QUALITIES.firstNotNullOfOrNull { quality ->
                    ByteArrayOutputStream().use { out -> it.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
                        .takeIf { bytes -> bytes.size <= CallDisplay.MAX_IMAGE_BYTES }
                }
            } finally { it.recycle() }
        }
        val identity = WearJson.encode(listOf(gateway.orEmpty(), target?.profile.orEmpty(), target?.sessionId.orEmpty()))
        val targetKey = MessageDigest.getInstance("SHA-256").digest(identity).joinToString("") { "%02x".format(it) }
        if (target?.profile != null && bytes == null && (!pictureKnown || picture != null)) {
            try {
                // A restart or failed fetch must not replace this bot's retained artwork with "no avatar".
                val node = Wearable.getNodeClient(context).localNode.await()
                val uri = Uri.Builder().scheme("wear").authority(node.id).path(WearPaths.CALL_DISPLAY).build()
                val item = Wearable.getDataClient(context).getDataItem(uri).await()
                val saved = item?.let { DataMapItem.fromDataItem(it).dataMap }
                val display = saved?.getString("display")?.let { WearJson.decode<CallDisplay>(it.encodeToByteArray()) }
                if (display?.valid == true && display.targetKey == targetKey && display.hasPicture && saved.getAsset("image") != null) return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return // Leave the retained snapshot intact until a later refresh can resolve it.
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(WearJson.encode(listOf(gateway.orEmpty(), target?.profile.orEmpty(), target?.sessionId.orEmpty(), target?.label ?: "New session")))
        bytes?.let(digest::update)
        val revision = digest.digest().joinToString("") { "%02x".format(it) }
        if (revision == published) return
        val display = CallDisplay(revision, (target?.label ?: "New session").take(256), bytes != null, targetKey = targetKey)
        val request = PutDataMapRequest.create(WearPaths.CALL_DISPLAY).apply {
            dataMap.putString("display", WearJson.encode(display).decodeToString())
            bytes?.let { dataMap.putAsset("image", Asset.createFromBytes(it)) }
        }.asPutDataRequest().setUrgent()
        try {
            Wearable.getDataClient(context).putDataItem(request).await()
            published = revision
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next observed settings/connection refresh retries this revision.
        }
    }

    private companion object {
        val JPEG_QUALITIES = listOf(90, 75, 60)
    }
}

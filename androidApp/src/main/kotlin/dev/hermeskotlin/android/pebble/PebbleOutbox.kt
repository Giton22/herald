package dev.hermeskotlin.android.pebble

import android.content.Context
import android.util.Log
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.wearlink.ChatList
import dev.hermeskotlin.wearlink.PebbleDict
import dev.hermeskotlin.wearlink.PebbleKeys
import dev.hermeskotlin.wearlink.PebbleLink
import dev.hermeskotlin.wearlink.PebbleSent
import dev.hermeskotlin.wearlink.PebbleValue
import dev.hermeskotlin.wearlink.PebbleWatch
import dev.hermeskotlin.wearlink.PhoneStatus
import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.ReplyUpdate
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [WatchOutbox] for Pebbles: what [dev.hermeskotlin.core.wear.WatchRelay] writes for a watch in WearLink's JSON goes
 * to the Pebble as the watchapp's dictionaries ([PebbleLink]), through the Pebble phone app. Each Pebble gets a
 * [PebbleWatch], which streams the reply in pieces and sends nothing while Kyllene isn't open on the watch.
 * Voice clips never go: a Pebble can't play them, and its asks never ask for them. A turn that ends while Kyllene
 * isn't open on the watch is told the way a phone's is, as the usual reply notification, which the Pebble app
 * shows on the watch.
 */
class PebbleOutbox(
    private val context: Context,
    private val scope: CoroutineScope,
    private val notifications: ChatNotifications,
    private val settings: SettingsStore,
) : WatchOutbox {

    private val sender by lazy { DefaultPebbleSender(context) }
    private val watches = ConcurrentHashMap<String, PebbleWatch>()

    /** The Pebble [node] names, its own sender made the first time. */
    fun watch(node: String): PebbleWatch = watches.getOrPut(node) { PebbleWatch(scope, { deliver(node, it) }, onUndelivered = ::notify) }

    private fun notify(update: ReplyUpdate) {
        val chat = update.chatId ?: return
        if (!(settings.settings.value ?: AppSettings()).notifyReplies) return
        val failed = update.phase == ReplyPhase.Failed
        val text = if (failed) update.error ?: update.text else update.text
        // Notifications are posted from the main thread, as the notifiers do.
        scope.launch(Dispatchers.Main) { notifications.postReply(chat, update.title, text, failed) }
    }

    override suspend fun send(node: String, path: String, data: ByteArray): Boolean {
        val watch = watch(node)
        when (path) {
            WearPaths.REPLY -> watch.reply(WearJson.decode<ReplyUpdate>(data) ?: return false)
            WearPaths.STATUS -> watch.message(PebbleLink.status(WearJson.decode<PhoneStatus>(data) ?: return false))
            WearPaths.CHATS -> {
                val list = WearJson.decode<ChatList>(data) ?: return false
                PebbleLink.chats(list, System.currentTimeMillis() / 1000.0).forEach(watch::message)
            }
            else -> return false
        }
        return true
    }

    /** Opens Kyllene on the Pebble [node] names; false when that can't be done (not connected, or no Pebble app). */
    suspend fun launch(node: String): Boolean {
        val watch = watchOf(node)
        val result = sender.startAppOnTheWatch(APP_UUID, listOf(watch))?.get(watch)
        Log.d(TAG, "Opening Kyllene on the Pebble: $result")
        return result == TransmissionResult.Success
    }

    override suspend fun stream(node: String, path: String, data: ByteArray): Boolean = false

    private suspend fun deliver(node: String, dict: PebbleDict): PebbleSent {
        val watch = watchOf(node)
        // Null: no Pebble phone app to send through (none installed, or none picked).
        val results = sender.sendDataToPebble(APP_UUID, dict.toPebbleKit(), listOf(watch)) ?: return PebbleSent.NotOpen
        return when (val result = results[watch]) {
            TransmissionResult.Success -> {
                Log.d(TAG, "To the Pebble: type ${dict[PebbleKeys.TYPE]}, phase ${dict[PebbleKeys.PHASE]}, text ${dict[PebbleKeys.TEXT_TOTAL]}, ${PebbleLink.size(dict)} bytes")
                PebbleSent.Sent
            }
            TransmissionResult.FailedDifferentAppOpen, TransmissionResult.FailedWatchNotConnected -> PebbleSent.NotOpen
            TransmissionResult.FailedNoPermissions -> {
                // The watchapp's package.json doesn't name this build of Kyllene.
                Log.w(TAG, "The Pebble app refused Kyllene for the watchapp")
                PebbleSent.NotOpen
            }
            else -> {
                Log.d(TAG, "Send to the Pebble failed: $result")
                PebbleSent.Failed
            }
        }
    }

    companion object {
        val APP_UUID: UUID = UUID.fromString(PebbleLink.APP_UUID)

        /** A Pebble's node in the relay, told apart from a Wear OS watch's by its prefix. */
        const val NODE_PREFIX = "pebble:"
        private const val TAG = "HeraldPebble"

        fun nodeOf(watch: WatchIdentifier): String = NODE_PREFIX + watch.value

        fun watchOf(node: String): WatchIdentifier = WatchIdentifier(node.removePrefix(NODE_PREFIX))

        fun isPebble(node: String): Boolean = node.startsWith(NODE_PREFIX)
    }
}

/** Numbers go as 32-bit integers: the watchapp reads each by its length, and the phone gets them back that way anyway. */
internal fun PebbleDict.toPebbleKit(): PebbleDictionary = entries.associate { (key, value) ->
    key.toUInt() to when (value) {
        is PebbleValue.Num -> PebbleDictionaryItem.Int32(value.value)
        is PebbleValue.Text -> PebbleDictionaryItem.Text(value.value)
    }
}

/** What the watch sent; bytes, which it never sends, are left out. */
internal fun PebbleDictionary.toPebbleLink(): PebbleDict = entries.mapNotNull { (key, item) ->
    val value = when (item) {
        is PebbleDictionaryItem.Text -> PebbleValue.Text(item.value)
        is PebbleDictionaryItem.Int8 -> PebbleValue.Num(item.value.toInt())
        is PebbleDictionaryItem.UInt8 -> PebbleValue.Num(item.value.toInt())
        is PebbleDictionaryItem.Int16 -> PebbleValue.Num(item.value.toInt())
        is PebbleDictionaryItem.UInt16 -> PebbleValue.Num(item.value.toInt())
        is PebbleDictionaryItem.Int32 -> PebbleValue.Num(item.value)
        is PebbleDictionaryItem.UInt32 -> PebbleValue.Num(item.value.toInt())
        is PebbleDictionaryItem.Bytes -> return@mapNotNull null
    }
    key.toInt() to value
}.toMap()
